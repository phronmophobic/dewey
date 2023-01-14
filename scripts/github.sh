
set -e
set -x

curl -O https://raw.githubusercontent.com/phronmophobic/dewey/main/scripts/bootstrap.sh
chmod 755 ./bootstrap.sh
source ./bootstrap.sh
clojure -Sdeps '{:deps {com.phronemophobic/dewey {:git/sha 3d4bfc2ade65b3b266805fd78c7d6dde9610c5d9, :git/url https://github.com/phronmophobic/dewey}}}' -M -e '(require (quote com.phronemophobic.dewey.etl))(ns com.phronemophobic.dewey.etl)(run 77540d51-0aac-4700-8e3c-458b1149d38d" \)
