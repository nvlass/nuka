(ns nuka.script
  (:require [clojure.string :as string]
            [clojure.walk :as walk]))

(defrecord TextArg [val])
(defrecord NumericArg [val])
(defrecord Flag [val])
(defrecord NamedArg [name val])
(defrecord Command [cmd args])
(defrecord EmbeddedCommand [cmd])
(defrecord Script [commands])
(defrecord Raw [val])

(defn- render-flag-name [f]
  (when f
    (if (string? f) f
        (let [s (name f)]
          (if (or (.startsWith s "--")
                  (.startsWith s "-"))
            s
            (if (= 1 (count s))
              (str "-" s)
              (str "--" s)))))))

(defmulti parse-arg-set
  (fn [x] (cond (record? x) (class x)
                (map? x) :map
                (vector? x) :vector
                (string? x) :string
                (keyword? x) :keyword
                (number? x) :number
                :else (class x))))

(defmethod parse-arg-set Command [x] (->EmbeddedCommand x))
(defmethod parse-arg-set :string [s] (TextArg. s))
(defmethod parse-arg-set :number [x] (NumericArg. x))
(defmethod parse-arg-set :keyword [k] (Flag. k))
(defmethod parse-arg-set :vector [v] (map parse-arg-set v))
(defmethod parse-arg-set :map [m]
  (mapcat (fn [[k v]] (cond (true? v)   [(Flag. k)] ;;keys with true values are just flags
                            (false? v)  [] ;;keys with false values are skipped
                            (keyword v) [(NamedArg. k (parse-arg-set (name v)))]
                            :else       [(NamedArg. k (parse-arg-set v))])) m))

(defn raw [x] (->Raw x))

(defn command [cmd & arg-sets]
  (->Command cmd (mapcat (fn [x]
                           (let [parsed (parse-arg-set x)]
                             (if (seq? parsed) parsed [parsed]))) arg-sets)))

(defn script* [& commands]
  (->Script commands))

(defn- unquote?
  "Tests whether the form is (clj ...) or (unquote ...) or ~expr."
  [form]
  (or (and (seq? form)
           (symbol? (first form))
           (= (symbol (name (first form))) 'clj))
      (and (seq? form) (= (first form) `unquote))))

(defn- command?
  [form]
  (and (not (unquote? form))
       (list? form)
       (symbol? (first form))))

(defmacro script [& commands]
  (def ss commands)
  `(apply script*
          ~(walk/postwalk
            (fn [form]
              (cond
                (unquote? form) (second form)
                (command? form) (concat (list 'command (str (first form))) (rest form))
                :else form)) (vec commands))))

(defn single-quote [s]
  (str "'" s "'"))

(defmulti render class)
(defmethod render Script [{:keys [commands]}] (string/join "\n" (map render commands)))
(defmethod render Command [{:keys [cmd args]}] (str cmd " " (string/join " " (map render args))))
(defmethod render EmbeddedCommand [{:keys [cmd]}] (str "$(" (render cmd) ")"))
(defmethod render TextArg [{:keys [val]}] (single-quote val))
(defmethod render NumericArg [{:keys [val]}] (str val))
(defmethod render Raw [{:keys [val]}] val)
(defmethod render Flag [{:keys [val]}] (render-flag-name val))
(defmethod render NamedArg [{:keys [name val]}] (str (render-flag-name name) " " (render val)))
