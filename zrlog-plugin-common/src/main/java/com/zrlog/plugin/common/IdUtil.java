package com.zrlog.plugin.common;

import java.util.Random;

public class IdUtil {

    public static int getInt() {
        return new Random().nextInt(Integer.MAX_VALUE);
    }
}
