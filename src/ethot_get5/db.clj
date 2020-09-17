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
    (doseq [i (range pbytes-len)]
      (aset-byte cljbytes i (- (py. pbytes __getitem__ i) 128)))
    cljbytes))

(defn import-team
  [team]
  (let [team-name (get team "name")
        team-tag (get-in team ["custom_fields" "tag"])
        team-flag (get-in team ["custom_fields" "flag"])
        auths (pickle-steam-ids (map #(get-in % ["custom_fields" "steam_id"]) (get team "lineup")))
        get5-id (:GENERATED_KEY (jdbc/execute-one! get5-web-ds ["insert into team (name,
                                                                                   tag,
                                                                                   flag,
                                                                                   auths,
                                                                                   public_team)
                                                                 values (?, ?, ?, ?, ?)"
                                                                team-name team-tag team-flag auths 0]
                                                   {:return-keys true}))]

    (jdbc/execute-one! ethot-ds ["insert into team (toornament_id, get5_id)
                                  values (?, ?)"
                                 (get team "id") get5-id]
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
  [match max-maps server-id plugin-version]
  (let [team1-toornament-id (get-in match ["opponents" 0 "participant" "id"])
        team2-toornament-id (get-in match ["opponents" 1 "participant" "id"])
        team1-id (toornament-to-get5-team-id team1-toornament-id)
        team2-id (toornament-to-get5-team-id team2-toornament-id)
        skip-veto (if (= max-maps 1) true false)
        api-key (gen-api-key)
        get5-id (:GENERATED_KEY (jdbc/execute-one! get5-web-ds ["insert into `match` (server_id,
                                                                                      team1_id,
                                                                                      team2_id,
                                                                                      plugin_version,
                                                                                      max_maps,
                                                                                      skip_veto,
                                                                                      veto_mappool,
                                                                                      api_key)
                                                                 values (?, ?, ?, ?, ?, ?, ?, ?)"
                                                                server-id
                                                                team1-id
                                                                team2-id
                                                                plugin-version
                                                                max-maps
                                                                skip-veto
                                                                map-pool
                                                                api-key]
                                                   {:return-keys true}))]
    (jdbc/execute-one! ethot-ds ["insert into `match` (toornament_id, get5_id)
                                  values (?, ?)"
                                 (get match "id") get5-id]
                       {:builder-fn rs/as-unqualified-lower-maps})
    (jdbc/execute-one! get5-web-ds ["update game_server
                                     set in_use = true
                                     where id = ?"
                                    server-id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn get-servers-not-in-use
  []
  (jdbc/execute! get5-web-ds ["select id from game_server
                               where in_use = false"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn match-imported?
  [toornament-id]
  (= (:c (jdbc/execute-one! ethot-ds ["select count(*) as c
                                       from `match`
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