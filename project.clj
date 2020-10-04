(defproject ethot-get5 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/data.json "1.0.0"]
                 [org.suskalo/discljord "0.2.5"]
                 [cheshire "5.10.0"]
                 [clj-http "3.10.0"]
                 [clj-python/libpython-clj "1.46"]
                 [counterstrafe/clj-rcon "0.1.1"]
                 [mysql/mysql-connector-java "8.0.19"]
                 [seancorfield/next.jdbc "1.0.409"]
                 [yogthos/config "1.1.7"]]
  ;; configuration will be read from the dev-config.edn file
  :jvm-opts ["-Dconfig=secrets.edn"]
  :main ethot-get5.core
  :repl-options {:init-ns ethot-get5.core}
  )
