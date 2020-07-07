package com.imooc.miaosha.controller;

import com.imooc.miaosha.access.AccessLimit;
import com.imooc.miaosha.rabbitmq.MQSender;
import com.imooc.miaosha.rabbitmq.MiaoshaMessage;
import com.imooc.miaosha.redis.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.domain.OrderInfo;
import com.imooc.miaosha.result.CodeMsg;
import com.imooc.miaosha.result.Result;
import com.imooc.miaosha.service.GoodsService;
import com.imooc.miaosha.service.MiaoshaService;
import com.imooc.miaosha.service.MiaoshaUserService;
import com.imooc.miaosha.service.OrderService;
import com.imooc.miaosha.vo.GoodsVo;
import sun.font.TrueTypeFont;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;

@Controller
@RequestMapping("/miaosha")
public class MiaoshaController implements InitializingBean {

    @Autowired
    MiaoshaUserService userService;

    @Autowired
    RedisService redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @Autowired
    MQSender sender;

    private static HashMap<Long, Boolean> localOverMap = new HashMap<Long, Boolean>();


    /**
     * 初始化时加载商品数量到缓存中，以及设置商品售空标识
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        List<GoodsVo> list = goodsService.listGoodsVo();
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (GoodsVo goods : list) {
            redisService.set(GoodsKey.getMiaoshaGoodsStock, ""+goods.getId(), goods.getStockCount());
            localOverMap.put(goods.getId(), false);
        }
    }


    /**
     * 重置
     *
     */
    @RequestMapping(value="/reset", method=RequestMethod.GET)
    @ResponseBody
    public Result<Boolean> reset() {
        List<GoodsVo> goodsList = goodsService.listGoodsVo();
        for(GoodsVo goods : goodsList) {
            goods.setStockCount(10);
            redisService.set(GoodsKey.getMiaoshaGoodsStock, ""+goods.getId(), 10);
            localOverMap.put(goods.getId(), false);
        }
        redisService.delete(OrderKey.getMiaoshaOrderByUidGid);
        redisService.delete(MiaoshaKey.isGoodsOver);
        miaoshaService.reset(goodsList);
        return Result.success(true);
    }


    /**
     * 获取秒杀地址
     * 秒杀的游戏规则是到了秒杀才能开始对商品下单购买，在此时间点之前，只能浏览商品信息，不能下单。而下单页面也是一个普通的URL，如果得到这个
     * URL，不用等到秒杀开始就可以下单了。
     * 解决方案：为了避免用户直接访问下单页面URL，需要将改URL动态化，即使秒杀系统的开发者也无法在秒杀开始前访问下单页面的URL。办法是在下单页
     * 面URL加入由服务器端生成的随机数作为参数，在秒杀开始的时候才能得到。
     * @param user
     * @param goodsId
     * @return
     */
    @AccessLimit(seconds = 5, maxCount = 5, needLogin = true)//限流 - 通用方法
    @RequestMapping(value="/path", method=RequestMethod.GET)
    @ResponseBody
    public Result<String> getMiaoshaPath(MiaoshaUser user,
                                         @RequestParam("goodsId")long goodsId,
                                         @RequestParam(value = "verifyCode", defaultValue = "0" ) int verifyCode) {
//        if(user == null) {
//            return Result.error(CodeMsg.SESSION_ERROR);
//        }
//        //限流 - 基本做法
//        Integer count = redisService.get(AccessKey.getAccessKey, user.getId() + ":" + goodsId, Integer.class);
//        if (count == null) {
//            redisService.set(AccessKey.getAccessKey, user.getId() + ":" + goodsId,1);
//        } else if (count < 5) {
//            redisService.incr(AccessKey.getAccessKey, user.getId() + ":" + goodsId);
//        } else {
//            return Result.error(CodeMsg.ACCESS_LIMIT_REACHED);
//        }

        boolean check = miaoshaService.checkVerifyCode(user, goodsId, verifyCode);
        if (!check) {
            return Result.error(CodeMsg.VERIFYCODE_ERROR);
        }

        String path  = miaoshaService.getMiaoshaPath(user, goodsId);
        return Result.success(path);
    }


    /**
     * QPS:1114
     * 1000 * 10
     *
     * 接口优化后:
     *  QPS:1623
     *  1000*10
     *
     * GET POST有什么区别？
     * GET:只有查询的时候使用，对数据有操作的不可用GET方式，比如delete?id=1这种方式是不对的，浏览器在获取列表的时候会遍历接口，可能会误删数据
     */
    @RequestMapping(value = "/{path}/do_miaosha", method = RequestMethod.POST)
    @ResponseBody
    public Result<Integer> miaosha(MiaoshaUser user,
                           @RequestParam("goodsId") long goodsId,
                           @PathVariable("path") String path) {
        if (user == null) {
            return Result.error(CodeMsg.SESSION_ERROR);
        }

        boolean truePath = miaoshaService.checkMiaoshaPath(user, goodsId, path);
        if (!truePath) {
            return Result.error(CodeMsg.REQUEST_ILLEGAL);
        }

        //内存标记，减少redis访问
        Boolean isOver = localOverMap.get(goodsId);
        if (isOver) {
            return Result.error(CodeMsg.MIAO_SHA_OVER);
        }

        //redis预减库存
        Long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
        if (stock < 0 ){
            localOverMap.put(goodsId, true);
            return Result.error(CodeMsg.MIAO_SHA_OVER);
        }

        //判断是否已经秒杀到了
        MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
        if (order != null) {
            return Result.error(CodeMsg.REPEATE_MIAOSHA);
        }

        //入队
        MiaoshaMessage mm = new MiaoshaMessage();
        mm.setUser(user);
        mm.setGoodsId(goodsId);
        sender.sendMiaoshaMessage(mm);
        return Result.success(0);


        /*
        //判断库存
        GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);//10个商品，req1 req2
        int stock = goods.getStockCount();
        if (stock <= 0) {
            return Result.error(CodeMsg.MIAO_SHA_OVER);
        }
        //判断是否已经秒杀到了
        MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
        if (order != null) {
            return Result.error(CodeMsg.REPEATE_MIAOSHA);
        }
        //减库存 下订单 写入秒杀订单
        OrderInfo orderInfo = miaoshaService.miaosha(user, goods);
        return Result.success(orderInfo);*/
    }

    /**
     * 轮询秒杀结果
     * orderId：成功
     * -1：秒杀失败
     *  0：排队中
     * */
    @RequestMapping(value="/result", method=RequestMethod.GET)
    @ResponseBody
    public Result<Long> miaoshaResult(MiaoshaUser user, @RequestParam("goodsId")long goodsId) {
        if(user == null) {
            return Result.error(CodeMsg.SESSION_ERROR);
        }
        long result  = miaoshaService.getMiaoshaResult(user.getId(), goodsId);
        return Result.success(result);
    }


    /**
     * 生成验证码
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping(value="/verifyCode", method=RequestMethod.GET)
    @ResponseBody
    public Result<String> verifyCode(HttpServletResponse response, MiaoshaUser user, @RequestParam("goodsId")long goodsId) {
        if(user == null) {
            return Result.error(CodeMsg.SESSION_ERROR);
        }
        try {
            BufferedImage image  = miaoshaService.createVerifyCode(user, goodsId);
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "JPEG", out);
            out.flush();
            out.close();
            return null;
        }catch(Exception e) {
            e.printStackTrace();
            return Result.error(CodeMsg.MIAOSHA_FAIL);
        }
    }



}
