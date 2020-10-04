(ns ethot-get5.toornament
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as hclient]
            [config.core :refer [env]])
  (:gen-class))

(def state (atom {:match-game-numbers {}}))

(def base-url "https://api.toornament.com")
(def toornament-api-key (:toornament-api-key env))
(def toornament-client-id (:toornament-client-id env))
(def toornament-client-secret (:toornament-client-secret env))

(defn process-response
  "Returns the JSON decoded body of the response."
  [resp]
  (json/read-str (:body resp)))

(defn oauth
  "Returns an access token with the provided scope."
  [scope]
  (let [url (str base-url "/oauth/v2/token")]
    (get (process-response
           (hclient/post url {:form-params {:grant_type "client_credentials"
                                            :client_id toornament-client-id
                                            :client_secret toornament-client-secret
                                            :scope (str "organizer:" scope)}}))
      "access_token")))

(defn tournaments
  "Returns all tournaments."
  []
  (let [url (str base-url "/organizer/v2/tournaments")]
    (process-response
      (hclient/get url {:headers {:X-Api-Key toornament-api-key
                                  :Authorization (oauth "view")
                                  :Range "tournaments=0-49"}}))))

(defn get-tournament
  "Returns the tournament."
  [name]
  (some #(when (= (get % "name") name) %) (tournaments)))

(defn stages
  "Returns all stages in the tournament."
  [tournament-id]
  (let [url (str base-url "/organizer/v2/tournaments/" tournament-id "/stages")]
    (process-response
      (hclient/get url {:headers {:X-Api-Key toornament-api-key
                                  :Authorization (oauth "result")}}))))

(defn get-stage
  "Returns the stage in the tournament."
  [tournament-id stage-name]
  (some #(when (= (get % "name") stage-name) %) (stages tournament-id)))

(defn matches
  "Returns all matches in the tournament."
  [tournament-id]
  (let [url (str base-url "/organizer/v2/tournaments/" tournament-id "/matches")]
    (process-response
      (hclient/get url {:headers {:X-Api-Key toornament-api-key
                                  :Authorization (oauth "result")
                                  :Range "matches=0-99"}}))))

(defn games
  "Returns all games in a match."
  [tournament-id match-id]
  (let [url (str base-url "/organizer/v2/tournaments/" tournament-id "/matches/" match-id "/games")]
    (process-response
      (hclient/get url {:headers {:X-Api-Key toornament-api-key
                                  :Authorization (oauth "result")
                                  :Range "games=0-49"}}))))

(defn single-game?
  "Returns true if the match contains a single game."
  [tournament-id match-id]
  (when (not (contains? (:match-game-numbers @state) tournament-id))
    (swap! state assoc-in [:match-game-numbers tournament-id] {}))
  (when (not (contains? (get (:match-game-numbers @state) tournament-id) match-id))
    (swap! state assoc-in [:match-game-numbers tournament-id match-id] (count (games tournament-id match-id))))
  (= (get-in @state [:match-game-numbers tournament-id match-id]) 1))

(defn importable-matches
  "Returns all matches that are ready to be imported into eBot."
  [tournament-id]
  (filter #(and (= (get % "status") "pending")
                (get-in % ["opponents" 0 "participant"])
                (get-in % ["opponents" 1 "participant"])
                (single-game? tournament-id (get % "id")))
          (matches tournament-id)))

(defn stage-matches
  "Returns all matches in the stage."
  [tournament-id stage-id]
  (filter #(= (get % "stage_id") stage-id) (matches tournament-id)))

(defn stage-complete?
  "Returns true if all matches in the stage are complete."
  [tournament-id stage-id]
  (reduce (fn [val match]
            (when (not val)
              (reduced false))
            (and val (= (get match "status") "completed")))
    true (stage-matches tournament-id stage-id)))

(defn participant
  "Returns the participant."
  [tournament-id participant-id]
  (let [url (str base-url "/organizer/v2/tournaments/" tournament-id "/participants/" participant-id)]
    (process-response
      (hclient/get url {:headers {:X-Api-Key toornament-api-key
                                  :Authorization (oauth "participant")}}))))

(defn participants
  "Returns all participants in the tournament."
  [tournament-id]
  (let [url (str base-url "/organizer/v2/tournaments/" tournament-id "/participants")]
    (process-response
      (hclient/get url {:headers {:X-Api-Key toornament-api-key
                                  :Authorization (oauth "participant")
                                  ;;FIX TO GET ALL PARTICIPANTS, INCLUDING THOSE NOT IN THIS RANGE
                                  :Range "participants=0-49"}}))))

(defn complete-game
  [tournament-id match-id game-number team1-score team2-score]
  (let [url (str base-url
                 "/organizer/v2/tournaments/" tournament-id
                 "/matches/" match-id
                 "/games/" game-number)]
    (process-response
      (hclient/patch url {:headers {:X-Api-Key toornament-api-key
                                    :Authorization (oauth "result")}
                          :form-params {:status "completed"
                                        :opponents [{:number 1
                                                     :score team1-score}
                                                    {:number 2
                                                     :score team2-score}]}
                          :content-type :json}))))