package com.imooc.miaosha.redis;

public class MiaoshaKey extends BasePrefix {

    private MiaoshaKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

    public static MiaoshaKey isGoodsOver = new MiaoshaKey( 0, "go");
    public static MiaoshaKey getMiaoshaPath = new MiaoshaKey( 30, "mp");
    public static MiaoshaKey getMiaoshaVerifyCode = new MiaoshaKey( 60, "mvc");

}
