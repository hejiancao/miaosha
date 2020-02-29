package com.imooc.miaosha.service;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.redis.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.util.MD5Util;
import com.imooc.miaosha.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.domain.OrderInfo;
import com.imooc.miaosha.vo.GoodsVo;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

@Service
public class MiaoshaService {

    private static final Logger log = LoggerFactory.getLogger(MiaoshaService.class);

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    RedisService redisService;

    @Transactional
    public OrderInfo miaosha(MiaoshaUser user, GoodsVo goods) {
        //减库存 下订单 写入秒杀订单
        boolean success = goodsService.reduceStock(goods);
        if (success) {
            //order_info maiosha_order
            //解决一个用户秒杀同一个商品多次，使用唯一索引
            return orderService.createOrder(user, goods);
        } else {
            setMiaoshaOver(goods.getId());
        }
        return null;
    }

    public long getMiaoshaResult(long userId, long goodsId) {
        MiaoshaOrder miaoshaOrder = orderService.getMiaoshaOrderByUserIdGoodsId(userId, goodsId);
        if (null != miaoshaOrder) {//秒杀成功
            return miaoshaOrder.getOrderId();
        } else {
            boolean isOver = getMiaoshaOver(goodsId);
            if (isOver) {//秒杀失败 -1
                return -1;
            } else {//排队中 0
                return 0;
            }
        }
    }

    private boolean getMiaoshaOver(long goodsId) {
        return redisService.exists(MiaoshaKey.isGoodsOver, "" + goodsId);
    }

    public void setMiaoshaOver(long goodsId) {
        redisService.set(MiaoshaKey.isGoodsOver, "" + goodsId, true);
    }

    public void reset(List<GoodsVo> goodsList) {
        goodsService.resetStock(goodsList);
        orderService.deleteOrders();
    }

    public String getMiaoshaPath(MiaoshaUser user, long goodsId) {
        if (goodsId <= 0) {
            return null;
        }

        String str = MD5Util.md5(UUIDUtil.uuid() + "123456");
        redisService.set(MiaoshaKey.getMiaoshaPath, ""+user.getId()+ ":" + goodsId, str);
        return str;
    }

    public boolean checkMiaoshaPath(MiaoshaUser user, long goodsId, String path) {
        if (path == null) {
            return false;
        }
        String str = redisService.get(MiaoshaKey.getMiaoshaPath, "" + user.getId() + ":" + goodsId, String.class);
        return path.equals(str);
    }


    public BufferedImage createVerifyCode(MiaoshaUser user, long goodsId) {
        if(user == null || goodsId <=0) {
            return null;
        }
        int width = 80;
        int height = 32;
        //create the image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        // set the background color
        g.setColor(new Color(0xDCDCDC));
        g.fillRect(0, 0, width, height);
        // draw the border
        g.setColor(Color.black);
        g.drawRect(0, 0, width - 1, height - 1);
        // create a random instance to generate the codes
        Random rdm = new Random();
        // make some confusion
        for (int i = 0; i < 50; i++) {
            int x = rdm.nextInt(width);
            int y = rdm.nextInt(height);
            g.drawOval(x, y, 0, 0);
        }
        // generate a random code
        String verifyCode = generateVerifyCode(rdm);
        g.setColor(new Color(0, 100, 0));
        g.setFont(new Font("Candara", Font.BOLD, 24));
        g.drawString(verifyCode, 8, 24);
        g.dispose();
        //把验证码存到redis中
        int rnd = calc(verifyCode);
        redisService.set(MiaoshaKey.getMiaoshaVerifyCode, user.getId()+":"+goodsId, rnd);
        //输出图片
        return image;
    }


    private static char[] ops = new char[] {'+', '-', '*'};

    /**
     * 图片验证码表达式
     * @param rdm
     * @return
     */
    private String generateVerifyCode(Random rdm) {
        int num1 = rdm.nextInt(10);
        int num2 = rdm.nextInt(10);
        int num3 = rdm.nextInt(10);
        char ops1 = ops[rdm.nextInt(3)];
        char ops2 = ops[rdm.nextInt(3)];
        String exp = "" + num1 + ops1 + num2 + ops2 + num3;
        log.info("exp = " + exp);
        return exp;
    }


    /**
     * 计算表达式结果
     * @param exp
     * @return
     */
    private static int calc(String exp) {
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");
            return (Integer)engine.eval(exp);
        }catch(Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public boolean checkVerifyCode(MiaoshaUser user, long goodsId, int verifyCode) {
        if (user == null || goodsId <= 0) {
            return false;
        }

        Integer val = redisService.get(MiaoshaKey.getMiaoshaVerifyCode, user.getId() + ":" + goodsId, Integer.class);
        if (val == null || (verifyCode - val) != 0){
            return false;
        }
        redisService.delete(MiaoshaKey.getMiaoshaVerifyCode, user.getId() + ":" + goodsId);
        return true;
    }
}
