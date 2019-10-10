# Maintainer: Stefan Silviu Alexandru <stefan.silviu.alexandru@gmail.com>
pkgname=ckompiler
pkgver=SNAPSHOT2
pkgrel=2
pkgdesc='A C11 compiler written in Kotlin'
arch=('any')
url='https://github.com/slak44/ckompiler'
license=('MIT')
depends=('java-environment')
optdepends=('nasm: for assembling compiled files'
            'graphviz: CFG viewing support')
makedepends=('gradle>=5.5' 'kotlin>=1.3.41')
provides=('ckompiler')
source=('https://github.com/slak44/ckompiler/archive/SNAPSHOT2.zip')
sha256sums=('e7f747f01ae007c6e1c8725249d6459ec30c2fa55e82478383518495e93b6e51')

build() {
  cd "$pkgname-$pkgver"
  gradle -g "./dot-gradle" build
}

check() {
  cd "$pkgname-$pkgver"
  gradle -g "./dot-gradle" check
}

package() {
  cd "$pkgname-$pkgver"
  DESTDIR="$pkgdir/" gradle -g "./dot-gradle" installDist
  # Install license
  install -d "$pkgdir/usr/share/licenses/$pkgname/"
  install -Dm644 LICENSE "$pkgdir/usr/share/licenses/$pkgname/"
  # Move the jars from /lib to inside /usr/share/java
  install -d "$pkgdir/usr/share/java/$pkgname/"
  mv "$pkgdir/lib" "$pkgdir/usr/share/java/$pkgname/"
  # Delete APP_HOME detection
  sed -i "25,43d" "$pkgdir/usr/bin/ckompiler"
  # Set the APP_HOME property to where we actually put the thing
  sed -i "25iAPP_HOME=\/usr\/share\/java\/$pkgname\/" "$pkgdir/usr/bin/ckompiler"
  # We're not on windows
  rm "$pkgdir/usr/bin/$pkgname.bat"
}
