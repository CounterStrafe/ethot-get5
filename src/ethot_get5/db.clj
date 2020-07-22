(ns ethot.db
  (:require [config.core :refer [env]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:gen-class))

(def db-name "ethot")
(def db-host (:mysql-host env))
(def db-user (:mysql-user env))
(def db-password (:mysql-pass env))

(def ds (jdbc/get-datasource
         {:dbtype "mysql"
          :dbname db-name
          :host db-host
          :user db-user
          :password db-password}))
