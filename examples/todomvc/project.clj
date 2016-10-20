(defproject todomvc-re-frame "0.8.0"
  :dependencies [[org.clojure/clojure        "1.8.0"]
                 [org.clojure/clojurescript  "1.9.89"]
                 [reagent "0.6.0-rc"]
                 [re-frame "0.8.1-SNAPSHOT"]
                 [binaryage/devtools "0.8.1"]
                 [secretary "1.2.3"]]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-figwheel "0.5.6"]]

  :hooks [leiningen.cljsbuild]

  :profiles {:dev  {:cljsbuild
                    {:builds {:client {:source-paths ["src" "../../src"]
                                       :compiler {:asset-path           "js"
                                                  :optimizations        :none
                                                  :source-map           true
                                                  :preloads             [dirac.runtime.preload]
                                                  :source-map-timestamp true
                                                  :main                 "todomvc.core"
                                                  :external-config      {:re-frisk {:enabled true}}}
                                       :figwheel {:on-jsload "todomvc.core/render"}}}}}

             :prod {:cljsbuild
                    {:builds {:client {:compiler {:optimizations :advanced
                                                  :elide-asserts true
                                                  :pretty-print  false}}}}}}

  :figwheel {:server-port 3451
             :repl        false}


  :clean-targets ^{:protect false} ["resources/public/js" "target"]

  :cljsbuild {:builds {:client {:source-paths ["src" "../../src"]
                                :compiler     {:output-dir "resources/public/js"
                                               :output-to  "resources/public/js/client.js"}}}})
