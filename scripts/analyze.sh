
set -e
set -x

SHA="$1"
RELEASE="$2"

sudo apt -y install sqlite3

java -XX:+PrintFlagsFinal -version 

clojure -Sdeps "{:deps {com.phronemophobic/dewey {:git/sha \"${SHA}\", :git/url \"https://github.com/phronmophobic/dewey\"}}}" -M --report stderr -e "(require (quote com.phronemophobic.dewey.etl))(ns com.phronemophobic.dewey.etl)(run-index \"${RELEASE}\" )"


