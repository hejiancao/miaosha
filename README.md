# 高并发秒杀系统
技术栈
- springboot
- rabbitmq
- redis

# 登录
前端密码传到后端服务器有可能被拦截，所以需要加密，这里我们采用MD5加密方式。
为了提高密码安全性，我们对密码进行加盐处理，并进行两次MD5。
```java
public class MD5Util {
	
	public static String md5(String src) {
		return DigestUtils.md5Hex(src);
	}
	
	private static final String salt = "1a2b3c4d";
	
	public static String inputPassToFormPass(String inputPass) {
		String str = ""+salt.charAt(0)+salt.charAt(2) + inputPass +salt.charAt(5) + salt.charAt(4);
		System.out.println(str);
		return md5(str);
	}
	
	public static String formPassToDBPass(String formPass, String salt) {
		String str = ""+salt.charAt(0)+salt.charAt(2) + formPass +salt.charAt(5) + salt.charAt(4);
		return md5(str);
	}
	
	public static String inputPassToDbPass(String inputPass, String saltDB) {
		String formPass = inputPassToFormPass(inputPass);
		String dbPass = formPassToDBPass(formPass, saltDB);
		return dbPass;
	}
	
	public static void main(String[] args) {
		System.out.println(inputPassToDbPass("123456", "1a2b3c4d"));//b7797cce01b4b131b433b6acf4add449
	}
	
}

```


# 参数验证
如果需要对手机号格式进行验证，可以自定义注解，结合validation进行自定义验证
1. 引入validation依赖
```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```
2. 自定义注解
```java
@Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER })
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = {IsMobileValidator.class })
public @interface  IsMobile {
	
	boolean required() default true;
	
	String message() default "手机号码格式错误";

	Class<?>[] groups() default { };

	Class<? extends Payload>[] payload() default { };
}
```
3. 编写自定义validator
```java
public class IsMobileValidator implements ConstraintValidator<IsMobile, String> {

	private boolean required = false;
	
	public void initialize(IsMobile constraintAnnotation) {
		required = constraintAnnotation.required();
	}

	public boolean isValid(String value, ConstraintValidatorContext context) {
		if(required) {
			return ValidatorUtil.isMobile(value);
		}else {
			if(StringUtils.isEmpty(value)) {
				return true;
			}else {
				return ValidatorUtil.isMobile(value);
			}
		}
	}

}
```

```java
public class ValidatorUtil {
	
	private static final Pattern mobile_pattern = Pattern.compile("1\\d{10}");
	
	public static boolean isMobile(String src) {
		if(StringUtils.isEmpty(src)) {
			return false;
		}
		Matcher m = mobile_pattern.matcher(src);
		return m.matches();
	}
	
//	public static void main(String[] args) {
//			System.out.println(isMobile("18912341234"));
//			System.out.println(isMobile("1891234123"));
//	}
}
```

# 接口优化
方案有如下几种：
- 页面缓存
- 对象缓存
- 前后端分离
- 库存预减+rabbitmq
- 隐藏秒杀地址
- 图形验证码
- 接口限流

## 页面缓存
使用页面缓存做优化，缺点是实时性有点差，可以在商品列表页面使用，商品列表更新慢一点也不会太影响用户体验
```java
    @RequestMapping(value="/to_list", produces="text/html")
    @ResponseBody
    public String list(HttpServletRequest request, HttpServletResponse response, Model model,MiaoshaUser user) {
    	model.addAttribute("user", user);
    	//取缓存
    	String html = redisService.get(GoodsKey.getGoodsList, "", String.class);
    	if(!StringUtils.isEmpty(html)) {
    		return html;
    	}
    	List<GoodsVo> goodsList = goodsService.listGoodsVo();
    	model.addAttribute("goodsList", goodsList);
    	SpringWebContext ctx = new SpringWebContext(request,response,
    			request.getServletContext(),request.getLocale(), model.asMap(), applicationContext );
    	//手动渲染
    	html = thymeleafViewResolver.getTemplateEngine().process("goods_list", ctx);
    	if(!StringUtils.isEmpty(html)) {
    		redisService.set(GoodsKey.getGoodsList, "", html);
    	}
    	return html;
    }
```

## 对象缓存

## 前后端分离

## 库存预减+rabbitmq
1. 继承InitializingBean类，在初始化的时候把商品数量加载到缓存中
2. 秒杀开始时，先从内存中预减库存，当库存可用时再把订单信息入队
3. mq消费者接收消息之后，减库存并创建订单

## 隐藏秒杀地址
前端代码是公开的，接口地址被黑客知道，可以通过工具进行攻击。如何实现接口隐藏，可以在秒杀接口地址上加一个随机码，用这个地址来当作秒杀进行时真正的请求地址

## 图形验证码
为了防止用户重复秒杀，每次请求时都需要输入验证码

## 接口限流
同样为了防止接口在短时间内被大量调用，比如限制5秒之内只允许同一用户请求5次，5秒之后重新计算请求次数



# 如何解决商品卖超问题
更新库存的时候加上库存大于0的条件
```java
@Update("update miaosha_goods set stock_count = stock_count - 1 where goods_id = #{goodsId} and stock_count > 0")
public int reduceStock(MiaoshaGoods g);
```

# 如何解决一个用户秒杀商品多次情况
数据库加唯一索引

