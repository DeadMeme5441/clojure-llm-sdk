(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.clojars.deadmeme5441/clojure-llm-sdk)
;; Version is the release coordinate. CI passes the git tag (minus the `v`)
;; via RELEASE_VERSION so the tag, the jar name, and the pom never drift;
;; local builds fall back to this literal.
(def version (or (System/getenv "RELEASE_VERSION") "0.6.1"))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def pom-data
  [[:description "Clojure SDK for canonical LLM provider integration."]
   [:url "https://github.com/DeadMeme5441/clojure-llm-sdk"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/license/mit"]]]
   [:developers
    [:developer
     [:id "DeadMeme5441"]
     [:email "deadmeme5441@gmail.com"]]]
   [:scm
    [:url "https://github.com/DeadMeme5441/clojure-llm-sdk"]
    [:connection "scm:git:https://github.com/DeadMeme5441/clojure-llm-sdk.git"]
    [:developerConnection "scm:git:ssh://git@github.com/DeadMeme5441/clojure-llm-sdk.git"]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/delete {:path class-dir})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :pom-data pom-data
                :src-dirs ["src"]
                :resource-dirs ["resources"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy
  "Build and publish the same versioned artifact. Requires Clojars credentials."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :sign-releases? false
    :pom-file (str class-dir "/META-INF/maven/" (namespace lib) "/"
                   (name lib) "/pom.xml")
    :artifact jar-file}))
