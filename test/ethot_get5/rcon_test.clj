(ns ethot_get5.rcon-test
  (:require [clojure.test :refer :all]
            [config.core :refer [env]]
            [ethot-get5.rcon :refer :all]))

(def test-server-host (:test-server-host env))
(def test-server-port (:test-server-port env))
(def test-server-rcon-password (:test-server-rcon-password env))

(deftest server-available-test
  (testing "server-available-test"
    (let [server {:server_id 1
                  :ip_string test-server-host
                  :port test-server-port
                  :rcon_password test-server-rcon-password}
          bad-server {:server_id 2
                      :ip_string "notahost"
                      :port 11111
                      :rcon_password "hi"}]
      (is (server-available? server))
      (is (not (server-available? bad-server))))))