
set -e
set -x

sudo apt -y update
sudo apt -y install emacs-lucid
sudo apt -y install openjdk-18-jre-headless
sudo apt -y install rlwrap
sudo apt -y install awscli
sudo apt -y install unzip
sudo apt -y install leiningen
NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

echo '# Set PATH, MANPATH, etc., for Homebrew.' >> /home/ubuntu/.profile
echo 'eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"' >> /home/ubuntu/.profile
eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"

sudo apt -y install build-essential
brew install gcc
brew install clojure/tools/clojure
