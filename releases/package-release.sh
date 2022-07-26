set -e
set -x



fname=$1

if [ -z "$fname" ]; then
    echo "usage: ./package-release.sh <fname>"
    exit 1
fi

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

cd "$fname"

tar cvzf deps.tar.gz deps
gzip *.edn
