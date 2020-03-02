package com.imooc.miaosha.redis;

public class AccessKey extends BasePrefix{

	private AccessKey(int expireSeconds, String prefix) {
		super(expireSeconds, prefix);
	}
	public static AccessKey getAccessKey = new AccessKey(5, "ak");

	public static AccessKey withExpireSeconds(int expireSeconds) {
		return new AccessKey(expireSeconds, "wes");
	}
}
