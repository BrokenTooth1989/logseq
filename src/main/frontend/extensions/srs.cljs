(ns frontend.extensions.srs
  (:require [frontend.template :as template]
            [frontend.db.query-dsl :as query-dsl]
            [frontend.db.query-react :as query-react]
            [frontend.util :as util]
            [frontend.util.property :as property]
            [frontend.util.drawer :as drawer]
            [frontend.util.persist-var :as persist-var]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.state :as state]
            [frontend.handler.editor :as editor-handler]
            [frontend.components.block :as component-block]
            [frontend.components.macro :as component-macro]
            [frontend.ui :as ui]
            [frontend.date :as date]
            [frontend.commands :as commands]
            [frontend.components.editor :as editor]
            [cljs-time.core :as t]
            [cljs-time.local :as tl]
            [cljs-time.coerce :as tc]
            [clojure.string :as string]
            [goog.object :as gobj]
            [rum.core :as rum]
            [frontend.modules.shortcut.core :as shortcut]
            [medley.core :as medley]))

;;; ================================================================
;;; Commentary
;;; - One block with tag "#card" or "[[card]]" is treated as a card.
;;; - {{cloze content}} show as "[...]" when reviewing cards

;;; ================================================================
;;; const & vars

;; TODO: simplify state

(defonce global-cards-mode? (atom false))

(def card-hash-tag "card")

(def card-last-interval-property        :card-last-interval)
(def card-repeats-property              :card-repeats)
(def card-last-reviewed-property        :card-last-reviewed)
(def card-next-schedule-property        :card-next-schedule)
(def card-last-easiness-factor-property :card-ease-factor)
(def card-last-score-property           :card-last-score)

(def default-card-properties-map {card-last-interval-property -1
                                  card-repeats-property 0
                                  card-last-easiness-factor-property 2.5})

(def cloze-macro-name
  "cloze syntax: {{cloze: ...}}"
  "cloze")

(def query-macro-name
  "{{cards ...}}"
  "cards")

(def learning-fraction-default
  "any number between 0 and 1 (the greater it is the faster the changes of the OF matrix)"
  0.5)

(defn- learning-fraction []
  (if-let [learning-fraction (:srs/learning-fraction (state/get-config))]
    (if (and (number? learning-fraction)
             (< learning-fraction 1)
             (> learning-fraction 0))
      learning-fraction
      learning-fraction-default)
    learning-fraction-default))

(def of-matrix (persist-var/persist-var nil "srs-of-matrix"))

(def initial-interval-default 4)

(defn- initial-interval []
  (if-let [initial-interval (:srs/initial-interval (state/get-config))]
    (if (and (number? initial-interval)
             (> initial-interval 0))
      initial-interval
      initial-interval-default)
    initial-interval-default))

;;; ================================================================
;;; utils

(defn- get-block-card-properties
  [block]
  (when-let [properties (:block/properties block)]
    (merge
     default-card-properties-map
     (select-keys properties  [card-last-interval-property
                               card-repeats-property
                               card-last-reviewed-property
                               card-next-schedule-property
                               card-last-easiness-factor-property
                               card-last-score-property]))))

(defn- save-block-card-properties!
  [block props]
  (editor-handler/save-block-if-changed!
   block
   (property/insert-properties (:block/format block) (:block/content block) props)
   {:force? true}))

(defn- reset-block-card-properties!
  [block]
  (save-block-card-properties! block {card-last-interval-property -1
                                      card-repeats-property 0
                                      card-last-easiness-factor-property 2.5
                                      card-last-reviewed-property "nil"
                                      card-next-schedule-property "nil"
                                      card-last-score-property "nil"}))


;;; used by other ns


(defn card-block?
  [block]
  (let [card-entity (db/entity [:block/name card-hash-tag])
        refs (into #{} (:block/refs block))]
    (contains? refs card-entity)))

(declare get-root-block)

;;; ================================================================
;;; sr algorithm (sm-5)
;;; https://www.supermemo.com/zh/archives1990-2015/english/ol/sm5

(defn- fix-2f
  [n]
  (/ (Math/round (* 100 n)) 100))

(defn- get-of [of-matrix n ef]
  (or (get-in of-matrix [n ef])
      (if (<= n 1)
        (initial-interval)
        ef)))

(defn- set-of [of-matrix n ef of]
  (->>
   (fix-2f of)
   (assoc-in of-matrix [n ef])))

(defn- interval
  [n ef of-matrix]
  (if (<= n 1)
    (get-of of-matrix 1 ef)
    (* (get-of of-matrix n ef)
       (interval (- n 1) ef of-matrix))))

(defn- next-ef
  [ef quality]
  (let [ef* (+ ef (- 0.1 (* (- 5 quality) (+ 0.08 (* 0.02 (- 5 quality))))))]
    (if (< ef* 1.3) 1.3 ef*)))

(defn- next-of-matrix
  [of-matrix n quality fraction ef]
  (let [of (get-of of-matrix n ef)
        of* (* of (+ 0.72 (* quality 0.07)))
        of** (+ (* (- 1 fraction) of) (* of* fraction))]
    (set-of of-matrix n ef of**)))

(defn next-interval
  "return [next-interval repeats next-ef of-matrix]"
  [_last-interval repeats ef quality of-matrix]
  (assert (and (<= quality 5) (>= quality 0)))
  (let [ef (or ef 2.5)
        next-ef (next-ef ef quality)
        next-of-matrix (next-of-matrix of-matrix repeats quality (learning-fraction) ef)
        next-interval (interval repeats next-ef next-of-matrix)]

    (if (< quality 3)
      ;; If the quality response was lower than 3
      ;; then start repetitions for the item from
      ;; the beginning without changing the E-Factor
      [-1 1 ef next-of-matrix]
      [(fix-2f next-interval) (+ 1 repeats) (fix-2f next-ef) next-of-matrix])))


;;; ================================================================
;;; card protocol


(defprotocol ICard
  (get-root-block [this]))

(defprotocol ICardShow
  ;; return {:value blocks :next-phase next-phase}
  (show-cycle [this phase])

  (show-cycle-config [this phase]))


(defn- has-cloze?
  [blocks]
  (->> (map :block/content blocks)
       (some #(string/includes? % "{{cloze "))))

(defn- clear-collapsed-property
  "Clear block's collapsed property if exists"
  [blocks]
  (let [result (map (fn [block]
                      (-> block
                          (dissoc :block/collapsed?)
                          (medley/dissoc-in [:block/properties :collapsed]))) blocks)]
    result))

;;; ================================================================
;;; card impl

(deftype Sided-Cloze-Card [block]
  ICard
  (get-root-block [_this] (db/pull [:block/uuid (:block/uuid block)]))
  ICardShow
  (show-cycle [_this phase]
    (let [blocks (-> (db/get-block-and-children (state/get-current-repo) (:block/uuid block))
                     clear-collapsed-property)
          cloze? (has-cloze? blocks)]
      (case phase
        1
        (let [blocks-count (count blocks)]
          {:value [block] :next-phase (if (or (> blocks-count 1) (nil? cloze?)) 2 3)})
        2
        {:value blocks :next-phase (if cloze? 3 1)}
        3
        {:value blocks :next-phase 1})))

  (show-cycle-config [_this phase]
    (case phase
      1
      {}
      2
      {}
      3
      {:show-cloze? true})))

(defn- ->card [block]
  {:pre [(map? block)]}
  (->Sided-Cloze-Card block))

;;; ================================================================
;;;

(defn- query
  "Use same syntax as frontend.db.query-dsl.
  Add an extra condition: block's :block/refs contains `#card or [[card]]'"
  ([repo query-string]
   (query repo query-string {}))
  ([repo query-string {:keys [disable-reactive? use-cache?]
                       :or {use-cache? true}}]
   (when (string? query-string)
     (let [query-string (template/resolve-dynamic-template! query-string)
           query-string (if (and (not (string/starts-with? query-string "("))
                                 (not (string/starts-with? query-string "[")))
                          (util/format "[[%s]]" (string/trim query-string))
                          query-string)
           {:keys [query sort-by rules]} (query-dsl/parse query-string)
           query* (concat [['?b :block/refs '?bp] ['?bp :block/name card-hash-tag]]
                          (if (coll? (first query))
                            query
                            [query]))]
       (when-let [query (query-dsl/query-wrapper query* true)]
         (let [result (query-react/react-query repo
                                               {:query (with-meta query {:cards-query? true})
                                                :rules (or rules [])}
                                               (merge
                                                {:use-cache? use-cache?}
                                                (cond->
                                                 (when sort-by
                                                   {:transform-fn sort-by})
                                                  disable-reactive?
                                                  (assoc :disable-reactive? true))))]
           (when result
             (flatten (util/react result)))))))))

(defn- query-scheduled
  "Return blocks scheduled to 'time' or before"
  [_repo blocks time]
  (let [filtered-result (filterv (fn [b]
                                   (let [props (:block/properties b)
                                         next-sched (get props card-next-schedule-property)
                                         next-sched* (tc/from-string next-sched)
                                         repeats (get props card-repeats-property)]
                                     (or (nil? repeats)
                                         (< repeats 1)
                                         (nil? next-sched)
                                         (nil? next-sched*)
                                         (t/before? next-sched* time))))
                                 blocks),
        sort-by-next-shedule   (sort-by (fn [b]
                                (get (get b :block/properties) card-next-schedule-property)) filtered-result)]
    {:total (count blocks)
     :result sort-by-next-shedule}))


;;; ================================================================
;;; operations


(defn- get-next-interval
  [card score]
  {:pre [(and (<= score 5) (>= score 0))
         (satisfies? ICard card)]}
  (let [block (.-block card)
        props (get-block-card-properties block)
        last-interval (or (util/safe-parse-float (get props card-last-interval-property)) 0)
        repeats (or (util/safe-parse-int (get props card-repeats-property)) 0)
        last-ef (or (util/safe-parse-float (get props card-last-easiness-factor-property)) 2.5)
        [next-interval next-repeats next-ef of-matrix*]
        (next-interval last-interval repeats last-ef score @of-matrix)
        next-interval* (if (< next-interval 0) 0 next-interval)
        next-schedule (tc/to-string (t/plus (tl/local-now) (t/hours (* 24 next-interval*))))
        now (tc/to-string (tl/local-now))]
    {:next-of-matrix of-matrix*
     card-last-interval-property next-interval
     card-repeats-property next-repeats
     card-last-easiness-factor-property next-ef
     card-next-schedule-property next-schedule
     card-last-reviewed-property now
     card-last-score-property score}))

(defn- operation-score!
  [card score]
  {:pre [(and (<= score 5) (>= score 0))
         (satisfies? ICard card)]}
  (let [block (.-block card)
        result (get-next-interval card score)
        next-of-matrix (:next-of-matrix result)]
    (reset! of-matrix next-of-matrix)
    (save-block-card-properties! (db/pull [:block/uuid (:block/uuid block)])
                                 (select-keys result
                                              [card-last-interval-property
                                               card-repeats-property
                                               card-last-easiness-factor-property
                                               card-next-schedule-property
                                               card-last-reviewed-property
                                               card-last-score-property]))))

(defn- operation-reset!
  [card]
  {:pre [(satisfies? ICard card)]}
  (let [block (.-block card)]
    (reset-block-card-properties! (db/pull [:block/uuid (:block/uuid block)]))))

(defn- operation-card-info-summary!
  [review-records review-cards card-query-block]
  (when card-query-block
    (let [review-count (count (flatten (vals review-records)))
          review-cards-count (count review-cards)
          score-5-count (count (get review-records 5))
          score-1-count (count (get review-records 1))]
      (editor-handler/insert-block-tree-after-target
       (:db/id card-query-block) false
       [{:content (util/format "Summary: %d items, %d review counts [[%s]]"
                               review-cards-count review-count (date/today))
         :children [{:content
                     (util/format "Remembered:   %d (%d%%)" score-5-count (* 100 (/ score-5-count review-count)))}
                    {:content
                     (util/format "Forgotten :   %d (%d%%)" score-1-count (* 100 (/ score-1-count review-count)))}]}]
       (:block/format card-query-block)))))

;;; ================================================================
;;; UI

(defn- dec-cards-due-count!
  []
  (state/update-state! :srs/cards-due-count
                       (fn [n]
                         (if (> n 0)
                           (dec n)
                           n))))

(defn- review-finished?
  [cards]
  (<= (count cards) 1))

(defn- score-and-next-card [score card *card-index cards *phase *review-records cb]
  (operation-score! card score)
  (swap! *review-records #(update % score (fn [ov] (conj ov card))))
  (if (review-finished? cards)
    (when cb (cb @*review-records))
    (reset! *phase 1))
  (swap! *card-index inc)
  (when @global-cards-mode?
    (dec-cards-due-count!)))

(defn- skip-card [card *card-index cards *phase *review-records cb]
  (swap! *review-records #(update % "skip" (fn [ov] (conj ov card))))
  (swap! *card-index inc)
  (if (review-finished? cards)
    (when cb (cb @*review-records))
    (reset! *phase 1)))

(def review-finished
  [:p.p-2 "Congrats, you've reviewed all the cards for this query, see you next time! 💯"])

(defn- btn-with-shortcut [{:keys [shortcut id btn-text background on-click]}]
  (ui/button
    [:span btn-text " " (ui/render-keyboard-shortcut shortcut)]
    :id id
    :class id
    :background background
    :on-click (fn [e]
                (when-let [elem (gobj/get e "target")]
                  (.add (.-classList elem) "opacity-25"))
                (js/setTimeout #(on-click) 10))))

(rum/defcs view
  < rum/reactive
  db-mixins/query
  (rum/local 1 ::phase)
  (rum/local {} ::review-records)
  {:will-mount (fn [state]
                 (state/set-state! :srs/mode? true)
                 state)
   :will-unmount (fn [state]
                   (state/set-state! :srs/mode? false)
                   state)}
  [state blocks {preview? :preview?
                 modal? :modal?
                 cb :callback}
   card-index]
  (let [blocks (if (fn? blocks) (blocks) blocks)
        cards (map ->card blocks)
        review-records (::review-records state)
        ;; TODO: needs refactor
        card (if preview?
               (when card-index (util/nth-safe cards @card-index))
               (first cards))]
    (if-not card
      review-finished
      (let [phase (::phase state)
            {blocks :value next-phase :next-phase} (show-cycle card @phase)
            root-block (.-block card)
            root-block-id (:block/uuid root-block)]
        [:div.ls-card.content
         {:class (when (or preview? modal?)
                   (str (util/hiccup->class ".flex.flex-col.resize.overflow-y-auto")
                        (when modal? " modal-cards")))
          :on-mouse-down (fn [e]
                           (util/stop e))}
         (let [repo (state/get-current-repo)]
           [:div {:style {:margin-top 20}}
            (component-block/breadcrumb {} repo root-block-id {})])
         (component-block/blocks-container
          blocks
          (merge (show-cycle-config card @phase)
                 {:id (str root-block-id)
                  :editor-box editor/box
                  :review-cards? true}))
         (if (or preview? modal?)
           [:div.flex.my-4.justify-between
            (when-not (and (not preview?) (= next-phase 1))
              (ui/button
                [:span (case next-phase
                         1 "Hide answers"
                         2 "Show answers"
                         3 "Show clozes")
                 (ui/render-keyboard-shortcut [:s])]
                :class "mr-2 card-answers"
                :on-click #(reset! phase next-phase)))
            (when (and (> (count cards) 1) preview?)
              (ui/button [:span "Next " (ui/render-keyboard-shortcut [:n])]
                :class "mr-2 card-next"
                :on-click (fn [e]
                            (util/stop e)
                            (skip-card card card-index cards phase review-records cb))))

            (when (and (not preview?) (= 1 next-phase))
              [:<>
               (btn-with-shortcut {:btn-text   "Forgotten"
                                   :shortcut   "f"
                                   :id         "card-forgotten"
                                   :background "red"
                                   :on-click   (fn []
                                                 (score-and-next-card 1 card card-index cards phase review-records cb)
                                                 (let [tomorrow (tc/to-string (t/plus (t/today) (t/days 1)))]
                                                   (editor-handler/set-block-property! root-block-id card-next-schedule-property tomorrow)))})

               (btn-with-shortcut {:btn-text (if (util/mobile?) "Hard" "Took a while to recall")
                                   :shortcut "t"
                                   :id       "card-recall"
                                   :on-click #(score-and-next-card 3 card card-index cards phase review-records cb)})

               (btn-with-shortcut {:btn-text   "Remembered"
                                   :shortcut   "r"
                                   :id         "card-remembered"
                                   :background "green"
                                   :on-click   #(score-and-next-card 5 card card-index cards phase review-records cb)})])

            (when preview?
              (ui/tippy {:html [:div.text-sm
                                "Reset this card so that you can review it immediately."]
                         :class "tippy-hover"
                         :interactive true}
                        (ui/button [:span "Reset"]
                          :id "card-reset"
                          :class (util/hiccup->class "opacity-60.hover:opacity-100.card-reset")
                          :on-click (fn [e]
                                      (util/stop e)
                                      (operation-reset! card)))))]
           [:div.my-3 (ui/button "Review cards" :small? true)])]))))

(rum/defc view-modal <
  (shortcut/mixin :shortcut.handler/cards)
  rum/reactive
  db-mixins/query
  [blocks option card-index]
  (let [option (update option :random-mode? (fn [v] (if (util/atom? v) @v v)))
        blocks (if (fn? blocks) (blocks) blocks)
        blocks (if (:random-mode? option)
                 (shuffle blocks)
                 blocks)]
    [:div#cards-modal
     (if (seq blocks)
       (view blocks option card-index)
       review-finished)]))

(rum/defc preview-cp
  [block-id]
  (let [blocks-f (fn [] (db/get-paginated-blocks (state/get-current-repo) block-id
                                                 {:scoped-block-id block-id}))]
    (view-modal blocks-f {:preview? true} (atom 0))))

(defn preview
  [block-id]
  (state/set-modal! #(preview-cp block-id) {:id :srs}))

;;; ================================================================
;;; register some external vars & related UI

;;; register cloze macro


(rum/defcs cloze-macro-show < rum/reactive
  {:init (fn [state]
           (let [config (first (:rum/args state))
                 shown? (atom (:show-cloze? config))]
             (assoc state :shown? shown?)))}
  [state config options]
  (let [shown?* (:shown? state)
        shown? (rum/react shown?*)
        toggle! #(swap! shown?* not)]
    (if (or shown? (:show-cloze? config))
      [:a.cloze-revealed {:on-click toggle!}
       (util/format "[%s]" (string/join ", " (:arguments options)))]
      [:a.cloze {:on-click toggle!}
       "[...]"])))

(component-macro/register cloze-macro-name cloze-macro-show)

(def cards-total (atom 0))

(defn get-srs-cards-total
  []
  (try
    (let [repo (state/get-current-repo)
          query-string ""
          blocks (query repo query-string {:use-cache?        false
                                           :disable-reactive? true})]
      (when (seq blocks)
        (let [{:keys [result]} (query-scheduled repo blocks (tl/local-now))
              count (count result)]
          (reset! cards-total count)
          count)))
    (catch js/Error e
      (js/console.error e) 0)))

;;; register cards macro
(rum/defcs ^:large-vars/cleanup-todo cards < rum/reactive db-mixins/query
  (rum/local 0 ::card-index)
  (rum/local false ::random-mode?)
  (rum/local false ::preview-mode?)
  [state config options]
  (let [*random-mode? (::random-mode? state)
        *preview-mode? (::preview-mode? state)
        repo (state/get-current-repo)
        query-string (string/join ", " (:arguments options))
        query-result (query repo query-string)
        *card-index (::card-index state)
        global? (:global? config)]
    (if (seq query-result)
      (let [{:keys [total result]} (query-scheduled repo query-result (tl/local-now))
            review-cards result
            card-query-block (db/entity [:block/uuid (:block/uuid config)])
            filtered-total (count result)
            ;; FIXME: It seems that model? is always true?
            modal? (:modal? config)
            callback-fn (fn [review-records]
                          (when-not @*preview-mode?
                            (operation-card-info-summary!
                             review-records review-cards card-query-block)
                            (persist-var/persist-save of-matrix)))]
        [:div.flex-1.cards-review {:style (when modal? {:height "100%"})
                                   :class (if global? "" "shadow-xl")}
         [:div.flex.flex-row.items-center.justify-between.cards-title
          [:div.flex.flex-row.items-center
           (if @*preview-mode?
             (ui/icon "book" {:style {:font-size 20}})
             (ui/icon "infinity" {:style {:font-size 20}}))
           [:div.ml-1.text-sm.font-medium (if (string/blank? query-string) "All" query-string)]]

          [:div.flex.flex-row.items-center

           ;; FIXME: CSS issue
           (if @*preview-mode?
             (ui/tippy {:html [:div.text-sm "current/total"]
                        :interactive true}
                       [:div.opacity-60.text-sm.mr-3
                        @*card-index
                        [:span "/"]
                        total])
             (ui/tippy {:html [:div.text-sm "overdue/total"]
                      ;; :class "tippy-hover"
                        :interactive true}
                       [:div.opacity-60.text-sm.mr-3
                        filtered-total
                        [:span "/"]
                        total]))

           (ui/tippy
            {:html [:div.text-sm "Toggle preview mode"]
             :delay [1000, 100]
             :class "tippy-hover"
             :interactive true
             :disabled false}
            [:a.opacity-60.hover:opacity-100.svg-small.inline.font-bold
             {:id "preview-all-cards"
              :style (when @*preview-mode? {:color "orange"})
              :on-click (fn [e]
                          (util/stop e)
                          (swap! *preview-mode? not)
                          (reset! *card-index 0))}
             "A"])

           (ui/tippy
            {:html [:div.text-sm "Toggle random mode"]
             :delay [1000, 100]
             :class "tippy-hover"
             :interactive true}
            [:a.mt-1.ml-2.block.opacity-60.hover:opacity-100
             {:on-mouse-down (fn [e]
                               (util/stop e)
                               (swap! *random-mode? not))}
             (ui/icon "arrows-shuffle" {:style (cond->
                                                {:font-size 18
                                                 :font-weight 600}
                                                 @*random-mode?
                                                 (assoc :color "orange"))})])]]
         (if (or @*preview-mode? (seq review-cards))
           [:div.px-1
            (when (and (not modal?) (not @*preview-mode?))
              {:on-click (fn []
                           (let [blocks-f (fn []
                                            (let [query-result (query repo query-string)]
                                              (:result (query-scheduled repo query-result (tl/local-now)))))]
                             (state/set-modal! #(view-modal
                                                 blocks-f
                                                 {:modal? true
                                                  :random-mode? *random-mode?
                                                  :preview? false
                                                  :callback callback-fn}
                                                 *card-index)
                                               {:id :srs})))})
            (let [view-fn (if modal? view-modal view)
                  blocks (if @*preview-mode?
                           (query repo query-string)
                           review-cards)]
              (view-fn blocks
               (merge config
                      {:global? global?
                       :random-mode? @*random-mode?
                       :preview? @*preview-mode?
                       :callback callback-fn})
               *card-index))]
           review-finished)])
      (if global?
        [:div.ls-card.content
         [:h1.title "Time to create a card!"]

         [:div
          [:p "You can add \"#card\" to any block to turn it into a card or trigger \"/cloze\" to add some clozes."]
          [:img.my-4 {:src "https://docs.logseq.com/assets/2021-07-22_22.28.02_1626964258528_0.gif"}]
          [:p "You can "
           [:a {:href "https://docs.logseq.com/#/page/cards" :target "_blank"}
            "click this link"]
           " to check the documentation."]]]
        [:div.opacity-60.custom-query-title.ls-card.content
         [:div.w-full.flex-1
          [:code.p-1 (str "Cards: " query-string)]]
         [:div.mt-2.ml-2.font-medium "No matched cards"]]))))

(rum/defc global-cards <
  {:will-mount (fn [state]
                 (reset! global-cards-mode? true)
                 state)
   :will-unmount (fn [state]
                   (reset! global-cards-mode? false)
                   state)}
  []
  (cards {:modal? true
          :global? true} {}))

(component-macro/register query-macro-name cards)

;;; register builtin properties
(property/register-built-in-properties #{card-last-interval-property
                                         card-repeats-property
                                         card-last-reviewed-property
                                         card-next-schedule-property
                                         card-last-easiness-factor-property
                                         card-last-score-property})

;;; register slash commands
(commands/register-slash-command ["Cards"
                                  [[:editor/input "{{cards }}" {:backward-pos 2}]]
                                  "Create a cards query"])

(commands/register-slash-command ["Cloze"
                                  [[:editor/input "{{cloze }}" {:backward-pos 2}]]
                                  "Create a cloze"])

;; handlers
(defn make-block-a-card!
  [block-id]
  (when-let [block (db/entity [:block/uuid block-id])]
    (when-let [content (:block/content block)]
      (let [content (-> (property/remove-built-in-properties (:block/format block) content)
                        (drawer/remove-logbook))]
        (editor-handler/save-block!
         (state/get-current-repo)
         block-id
         (str (string/trim content) " #" card-hash-tag))))))

(defn update-cards-due-count!
  []
  (js/setTimeout
   (fn []
     (let [total (get-srs-cards-total)]
       (state/set-state! :srs/cards-due-count total)))
   200))
