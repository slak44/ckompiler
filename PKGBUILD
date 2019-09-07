# Maintainer: Stefan Silviu <stefan.silviu.alexandru@gmail.com>
pkgname=ckompiler
pkgver=SNAPSHOT1
pkgrel=1
pkgdesc='A C11 compiler written in Kotlin'
arch=('any')
url='https://github.com/slak44'
license=('MIT')
depends=('java-environment')
optdepends=('nasm: for assembling compiled files'
            'graphviz: CFG viewing support')
makedepends=('gradle>=5.5' 'kotlin>=1.3.41')
provides=('ckompiler')
source=('https://github.com/slak44/ckompiler/archive/SNAPSHOT1.zip')
sha256sums=('4c57f063a2d1e8eb8ba5fd1fe723643f84870a14ebaa4a0e5d187c49fdac76f4')

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
  install -d "$pkgdir/usr/share/licenses/$pkgname/"
  install -Dm644 LICENSE "$pkgdir/usr/share/licenses/$pkgname/"
  # Move the jars from /lib to inside /usr/share/java
  install -d "$pkgdir/usr/share/java/$pkgname/lib"
  mv "$pkgdir/lib" "$pkgdir/usr/share/java/$pkgname/lib"
  # Set the APP_HOME property to where we actually put the thing
  sed -i "s/cd \"\$SAVED\" >\/dev\/null/APP_HOME=\/usr\/share\/java\/$pkgname/" "$pkgdir/usr/bin/ckompiler"
  # We're not on windows
  rm "$pkgdir/usr/bin/$pkgname.bat"
}
