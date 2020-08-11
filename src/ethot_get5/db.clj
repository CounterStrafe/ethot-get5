(ns ethot.db
  (:require [config.core :refer [env]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:gen-class))

(def db-host (:mysql-host env))
(def db-user (:mysql-user env))
(def db-password (:mysql-pass env))
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

(def add-team
  [team]
  (let [team-name (get team "name")
        team-tag (get-in team ["custom_fields" "tag"])
        team-flag (get-in team ["custom_fields" "flag"])]
    (jdbc/execute-one! get5-web-ds ["insert into team (user_id, name, tag, flag, public_team)
                                     values (?, ?, ?, ?, ?)"
                                    user-id team-name team-tag team-flag 0]
                       {:builder-fn rs/as-unqualified-lower-maps}))