package com.imooc.miaosha.access;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessLimit {
    int seconds();
    int maxCount();
    boolean needLogin() default true;
}
