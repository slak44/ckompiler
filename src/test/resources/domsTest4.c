int main() {
  int x = 1;
  if (x > 0) {
    x += 2;
    loop: {
      x += 3;
      if (x > 87) goto loop;
      else goto loopEnd;
    }
    loopEnd:
    x += 4;
    goto end;
  } else {
    start5:
    x += 5;
    if (x > 44) {
      x += 6;
      if (x < 5678) goto eight;
      else goto loopEnd;
    } else {
      x += 7;
    }
    eight:
    x += 8;
    if (x > 22) goto start5;
    goto end;
  }
  end:
  return x;
}
