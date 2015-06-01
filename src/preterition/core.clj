(ns preterition.core
  (:require [clojure.string :refer [join split]]
            [me.raynes.fs :refer [file walk]]
            [optimus.export :as export]
            [optimus.assets :as assets]
            [optimus.optimizations :as optimizations]
            [preterition.assets :refer [export-assets]]
            [preterition.config :refer [configs path-prefix]]
            [preterition.render :refer [render-all]]
            [preterition.repo :refer :all]
            [preterition.parse :refer [parse strip-ext]]
            [preterition.database :as db]))

(def ^:private filter-markdown-files
  (partial filter (partial re-matches #".*\.(md|markdown)$")))

(defn- slurp-file [repo-name filename]
  (->> filename
       (str path-prefix repo-name "/")
       (slurp)))

(defn- get-document [repo-name filename]
  (->> (slurp-file repo-name filename)
       (parse filename)))

(defn- get-all-markdown-filenames [dir]
  (let [current-path (.getPath (file dir))
        prefix-dir-count (count (split current-path #"\/"))]
    (->> dir
         (walk
           (fn [root dirs files]
             (let [root-path (.getPath root)]
               (map #(-> (str root-path "/" %)
                         (split #"\/")
                         ((partial drop prefix-dir-count))
                         ((partial join "/")))
                    files))))
         flatten
         filter-markdown-files)))

(defn get-all-documents [conf]
  (let [repo-name (conf :repo)]
    (->> (get-all-markdown-filenames (str path-prefix repo-name))
         (map (partial get-document repo-name)))))

(defn get-document-set [conf]
  (let [repo (get-repo (conf :repo) (conf :branch))
        [get-commit get-change-list] (map #(partial % repo) [get-commit get-change-list])
        get-document (partial get-document (conf :repo))
        get-documents (partial map get-document)
        head-commit (get-head-commit repo)
        head-commit-map (-> (get-commit-map head-commit)
                            (merge (select-keys conf [:repository :username])))]
    (if-let [newest-commit-hash ((db/get-newest-commit-map) :git-commit-hash)]
      (if (not= newest-commit-hash (head-commit-map :git-commit-hash))
        (let [newest-commit (get-commit newest-commit-hash)
              change-set (get-change-list newest-commit head-commit)]
          (-> (merge change-set {:git-commit head-commit-map})
              (update-in [:add] get-documents)
              (update-in [:edit] get-documents)
              (update-in [:delete] (partial map strip-ext)))))
      (->> (get-all-documents conf)
           (assoc {:git-commit head-commit-map} :add)))))

(defn on-post [repo]
  (if-let [config (configs repo)]
    (if-let [document-set (get-document-set config)]
      (do
        (db/update document-set)
        document-set)
      "nothing new")))

(defn get-assets []
  (concat
    (assets/load-bundle "./" "main.js" ["/js/main.js"])
    (assets/load-assets "images" [#"/img/.+\.jpg|png$"])
    (assets/load-bundle "./" "style.css" ["/css/style.css"])))

(defn export-assets []
  (-> (get-assets)
      (optimizations/minify-js-assets nil)
      (optimizations/minify-css-assets {:clean-css {:keep-special-comments 0}})
      (optimizations/inline-css-imports)
      (optimizations/concatenate-bundles)
      (export/save-assets "./resources/public/")))

(export-assets)
(render-all)
