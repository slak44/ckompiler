// C standard: 7.27.1

#define TIME_UTC 1

struct timespec {
  __SIZE_T_TYPE tv_sec;
  long tv_nsec;
};

// C standard: 7.27.2.5
int timespec_get(struct timespec *ts, int base);
