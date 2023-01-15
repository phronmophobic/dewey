
set -e
set -x

apt -y update
apt -y install emacs-lucid
apt -y install openjdk-18-jre-headless
apt -y install rlwrap
apt -y install awscli
apt -y install unzip
apt -y install leiningen
apt -y install git
NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

echo '# Set PATH, MANPATH, etc., for Homebrew.' >> /home/ubuntu/.profile
echo 'eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"' >> /home/ubuntu/.profile
eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"

apt -y install build-essential
brew install gcc
brew install clojure/tools/clojure
