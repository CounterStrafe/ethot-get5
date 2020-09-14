(ns ethot-get5.db
  (:require [clojure.string :as str]
            [config.core :refer [env]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [libpython-clj.require :refer [require-python]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:gen-class))

(require-python '[builtins :as python])
(require-python 'pickle)

(def db-host (:mysql-host env))
(def db-user (:mysql-user env))
(def db-password (:mysql-pass env))
(def import-blacklist (:import-blacklist env))
(def map-pool (:map-pool env))
(def python-executable (:python-executable env))
(def user-id (:get5-web-user-id env))

(def ethot-ds (jdbc/get-datasource
               {:dbtype "mysql"
                :dbname "ethot"
                :host db-host
                :user db-user
                :password db-password}))

(def get5-web-ds (jdbc/get-datasource
                  {:dbtype "mysql"
                   :dbname "get5-web"
                   :host db-host
                   :user db-user
                   :password db-password}))

(defn pickle-steam-ids
  [steam-ids]
  (let [plist (py/->py-list steam-ids)
        pbytes (pickle/dumps plist)
        pbytes-len (python/len pbytes)
        cljbytes (byte-array pbytes-len)]
    (for [i (range pbytes-len)] (aset-byte cljbytes i (- (py. pbytes __getitem__ i) 128)))))

(defn import-team
  [team]
  (let [team-name (get team "name")
        team-tag (get-in team ["custom_fields" "tag"])
        team-flag (get-in team ["custom_fields" "flag"])
        auths (pickle-steam-ids (map #(get-in % ["custom_fields" "steam_id"]) (get team "lineup")))]
    (jdbc/execute-one! get5-web-ds ["insert into team (user_id,
                                                       name,
                                                       tag,
                                                       flag,
                                                       auths,
                                                       public_team)
                                     values (?, ?, ?, ?, ?)"
                                    user-id team-name team-tag team-flag auths 0]
                       {:builder-fn rs/as-unqualified-lower-maps})

    (jdbc/execute-one! ethot-ds ["insert into team (toornament_id, get5_id)
                                  values (?, ?)"
                                 (get team "id")]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn toornament-to-get5-team-id
  [toornament-id]
  (:get5_id (jdbc/execute-one! ethot-ds ["select get5_id from team
                                          where toornament_id = ?"
                                         toornament-id]
                               {:builder-fn rs/as-unqualified-lower-maps})))

(defn gen-api-key
  "Returns a randomly generated 24-character alphanumeric string."
  []
  (clojure.string/join (take 24 (repeatedly #(get "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" (rand-int 36))))))

(defn import-match
  [match games server-id plugin-version]
  (let [team1-toornament-id (get-in match ["opponents" 0 "participant" "id"])
        team2-toornament-id (get-in match ["opponents" 1 "participant" "id"])
        team1-id (toornament-to-get5-team-id team1-toornament-id)
        team2-id (toornament-to-get5-team-id team2-toornament-id)
        max-maps (count games)
        skip-veto (if (= max-maps 1) true false)
        api-key (gen-api-key)]
    (jdbc/execute-one! get5-web-ds ["insert into match (user_id,
                                                        server_id,
                                                        team1_id,
                                                        team2_id,
                                                        max_maps,
                                                        skip_veto,
                                                        veto_mappool,
                                                        api_key)
                                     values (?, ?, ?, ?, ?, ?, ?, ?)"
                                    user-id
                                    server-id
                                    team1-id
                                    team2-id
                                    max-maps
                                    skip-veto
                                    map-pool
                                    api-key]
                       {:builder-fn rs/as-unqualified-lower-maps}
    ; Create match db entry in ethot
    ; Set server db entry in_use
    )))

(defn match-imported?
  [toornament-id]
  (= (:c (jdbc/execute-one! ethot-ds ["select count(*) as c
                                       from match
                                       where toornament_id = ?" toornament-id]
                            {:builder-fn rs/as-unqualified-lower-maps}))
     0))

(defn match-delayed?
  "Checks if a match is delayed."
  [match-id]
  (not= (:c (jdbc/execute-one! ethot-ds ["select count(*) as c
                                          from delays
                                          where match_id = ?" match-id]
                               {:builder-fn rs/as-unqualified-lower-maps}))
        0))