package cn.zlianpay.reception.controller;

import cn.zlianpay.carmi.entity.Cards;
import cn.zlianpay.carmi.entity.OrderCard;
import cn.zlianpay.carmi.service.CardsService;
import cn.zlianpay.carmi.service.OrderCardService;
import cn.zlianpay.common.core.web.JsonResult;
import cn.zlianpay.orders.vo.OrderVos;
import cn.zlianpay.reception.dto.SearchDTO;
import cn.zlianpay.settings.entity.Coupon;
import cn.zlianpay.settings.entity.ShopSettings;
import cn.zlianpay.settings.service.CouponService;
import cn.zlianpay.settings.service.ShopSettingsService;
import cn.zlianpay.settings.vo.PaysVo;
import cn.zlianpay.theme.entity.Theme;
import cn.zlianpay.theme.service.ThemeService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.zlianpay.orders.entity.Orders;
import cn.zlianpay.orders.service.OrdersService;
import cn.zlianpay.orders.vo.OrdersVo;
import cn.zlianpay.products.entity.Classifys;
import cn.zlianpay.products.entity.Products;
import cn.zlianpay.products.service.ClassifysService;
import cn.zlianpay.products.service.ProductsService;
import cn.zlianpay.products.vo.ClassifysVo;
import cn.zlianpay.products.vo.ProductsVo;
import cn.zlianpay.products.vo.ProductsVos;
import cn.zlianpay.settings.entity.Pays;
import cn.zlianpay.settings.service.PaysService;
import cn.zlianpay.website.entity.Website;
import cn.zlianpay.website.service.WebsiteService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mobile.device.Device;
import org.springframework.mobile.device.DevicePlatform;
import org.springframework.mobile.device.DeviceUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Controller
public class IndexController {

    @Autowired
    private ProductsService productsService;

    @Autowired
    private ClassifysService classifysService;

    @Autowired
    private CardsService cardsService;

    @Autowired
    private PaysService paysService;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private OrderCardService orderCardService;

    @Autowired
    private WebsiteService websiteService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private ThemeService themeService;

    @Autowired
    private ShopSettingsService shopSettingsService;

    @RequestMapping({"/", "/index"})
    public String IndexView(Model model) {

        QueryWrapper<Classifys> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", 1);
        queryWrapper.orderByAsc("sort");

        List<Classifys> classifysList = classifysService.list(queryWrapper);

        AtomicInteger index = new AtomicInteger(0);
        List<ClassifysVo> classifysVoList = classifysList.stream().map((classifys) -> {
            ClassifysVo classifysVo = new ClassifysVo();
            BeanUtils.copyProperties(classifys, classifysVo);
            int count = productsService.count(new QueryWrapper<Products>().eq("classify_id", classifys.getId()).eq("status", 1));
            classifysVo.setProductsMember(count);
            int andIncrement = index.getAndIncrement();
            classifysVo.setAndIncrement(andIncrement); // 索引
            return classifysVo;
        }).collect(Collectors.toList());
        model.addAttribute("classifysListJson", JSON.toJSONString(classifysVoList));

        Website website = websiteService.getById(1);
        model.addAttribute("website", website);

        ShopSettings shopSettings = shopSettingsService.getById(1);
        model.addAttribute("isBackground", shopSettings.getIsBackground());
        model.addAttribute("shopSettings", JSON.toJSONString(shopSettings));
        model.addAttribute("shop", shopSettings);

        Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
        return "theme/" + theme.getDriver() + "/index.html";
    }

    @ResponseBody
    @RequestMapping("/getProductList")
    public JsonResult getProductList(Integer classifyId) {

        /**
         * 条件构造器
         * 根据分类id查询商品
         * 状态为开启
         * asc 排序方式
         */
        QueryWrapper<Products> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("classify_id", classifyId);
        queryWrapper.eq("status", 1);
        queryWrapper.orderByAsc("sort");

        List<Products> productsList = productsService.list(queryWrapper);
        List<ProductsVo> productsVoList = productsList.stream().map((products) -> {
            ProductsVo productsVo = new ProductsVo();
            BeanUtils.copyProperties(products, productsVo);
            int count = cardsService.count(new QueryWrapper<Cards>().eq("product_id", products.getId()).eq("status", 0));
            productsVo.setCardMember(count);
            int count2 = cardsService.count(new QueryWrapper<Cards>().eq("product_id", products.getId()).eq("status", 1));
            productsVo.setSellCardMember(count2);
            productsVo.setPrice(products.getPrice().toString());

            int count1 = couponService.count(new QueryWrapper<Coupon>().eq("product_id", products.getId()));
            productsVo.setIsCoupon(count1);

            if (products.getShipType() == 1) {
                productsVo.setCardMember(products.getInventory());
                productsVo.setSellCardMember(products.getSales());
            }

            return productsVo;
        }).collect(Collectors.toList());
        return JsonResult.ok("ok").setData(productsVoList);
    }

    /**
     * 商品购买页面
     *
     * @param model
     * @param link
     * @return
     */
    @RequestMapping("/product/{link}")
    public String product(Model model, @PathVariable("link") String link, HttpServletRequest request) {
        // 查出商品
        Products products = productsService.getOne(new QueryWrapper<Products>().eq("link", link));
        // 查出分类
        Classifys classifys = classifysService.getById(products.getClassifyId());

        Device currentDevice = DeviceUtils.getCurrentDevice(request);
        DevicePlatform devicePlatform = currentDevice.getDevicePlatform();
        Integer isMobile;
        if (devicePlatform.name().equals("IOS") || devicePlatform.name().equals("ANDROID")) {
            isMobile = 1;
        } else {
            isMobile = 0;
        }
        Pays isWxPays = paysService.getOne(new QueryWrapper<Pays>().eq("driver", "wxpay").eq("enabled", 1));
        model.addAttribute("isWxPays", isWxPays);
        Pays isWxH5Pays = paysService.getOne(new QueryWrapper<Pays>().eq("driver", "wxpay_h5").eq("enabled", 1));
        model.addAttribute("isWxH5Pays", isWxH5Pays);
        model.addAttribute("isMobile", isMobile);
        model.addAttribute("products", products);
        model.addAttribute("classifyName", classifys.getName());

        AtomicInteger index = new AtomicInteger(0);
        /**
         * 查出启用的支付驱动列表
         */
        List<Pays> paysList = paysService.list(new QueryWrapper<Pays>().eq("enabled", 1));
        List<PaysVo> paysVoList = paysList.stream().map((pays) -> {
            PaysVo paysVo = new PaysVo();
            BeanUtils.copyProperties(pays, paysVo);
            int andIncrement = index.getAndIncrement();
            paysVo.setAndIncrement(andIncrement); // 索引
            return paysVo;
        }).collect(Collectors.toList());

        model.addAttribute("paysList", paysVoList);

        if (products.getIsWholesale() == 1) {
            String wholesale = products.getWholesale();
            String[] wholesales = wholesale.split("\\n");

            List<Map<String, String>> list = new ArrayList<>();
            AtomicInteger atomicInteger = new AtomicInteger(0);
            for (String s : wholesales) {
                String[] split = s.split("=");
                Map<String, String> map = new HashMap<>();
                Integer andIncrement = atomicInteger.getAndIncrement();
                map.put("id", andIncrement.toString());
                map.put("number", split[0]);
                map.put("money", split[1]);
                list.add(map);
            }
            model.addAttribute("wholesaleList", list);
        }

        int isCoupon = couponService.count(new QueryWrapper<Coupon>().eq("product_id", products.getId()));
        model.addAttribute("isCoupon", isCoupon);

        Website website = websiteService.getById(1);
        model.addAttribute("website", website);

        ShopSettings shopSettings = shopSettingsService.getById(1);
        model.addAttribute("isBackground", shopSettings.getIsBackground());

        Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
        return "theme/" + theme.getDriver() + "/product.html";
    }

    @ResponseBody
    @RequestMapping("/getProductById")
    public JsonResult getProductById(Integer id) {
        Products products = productsService.getById(id);

        ProductsVos productsVos = new ProductsVos();
        BeanUtils.copyProperties(products, productsVos);
        productsVos.setId(products.getId());
        productsVos.setPrice(products.getPrice().toString());

        if (products.getShipType() == 0) { // 自动发货模式
            Integer count = getCardListCount(cardsService, products); // 计算卡密使用情况
            productsVos.setCardsCount(count.toString());
        } else { // 手动发货模式
            productsVos.setCardsCount(products.getInventory().toString());
        }

        return JsonResult.ok().setData(productsVos);
    }

    /**
     * 计算卡密使用情况
     *
     * @param cardsService
     * @param products
     * @return
     */
    public static Integer getCardListCount(CardsService cardsService, Products products) {
        List<Cards> cardsList = cardsService.list(new QueryWrapper<Cards>().eq("product_id", products.getId()));
        Integer count = 0;
        for (Cards cards : cardsList) {
            if (cards.getStatus() == 0) {
                count++;
            }
        }
        return count;
    }

    @RequestMapping("/search")
    public String search(Model model, HttpServletRequest request) {

        Cookie[] cookies = request.getCookies();
        if (!ObjectUtils.isEmpty(cookies)) {
            for (Cookie cookie : cookies) {
                String cookieName = cookie.getName();
                if ("BROWSER_ORDERS_CACHE".equals(cookieName)) {
                    String cookieValue = cookie.getValue();
                    boolean contains = cookieValue.contains("=");
                    if (contains) {
                        String[] split = cookieValue.split("=");
                        List<SearchDTO> ordersList = new ArrayList<>();
                        AtomicInteger index = new AtomicInteger(0);
                        for (String s : split) {
                            Orders member = ordersService.getOne(new QueryWrapper<Orders>().eq("member", s));

                            if (ObjectUtils.isEmpty(member)) {
                                continue;
                            }

                            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");//设置日期格式
                            String date = df.format(member.getCreateTime());// new Date()为获取当前系统时间，也可使用当前时间戳

                            SearchDTO searchDTO = new SearchDTO();
                            searchDTO.setId(member.getId().toString());
                            Integer andIncrement = (Integer) index.getAndIncrement();
                            searchDTO.setAndIncrement(andIncrement.toString());
                            searchDTO.setCreateTime(date);
                            searchDTO.setMoney(member.getMoney().toString());
                            if (member.getPayType().equals("mqpay_alipay")
                                    || member.getPayType().equals("zlianpay_alipay")
                                    || member.getPayType().equals("yungouos_alipay")
                                    || member.getPayType().equals("xunhupay_alipay")
                                    || member.getPayType().equals("jiepay_alipay")
                                    || member.getPayType().equals("payjs_alipay")
                                    || member.getPayType().equals("yunfu_alipay")
                                    || member.getPayType().equals("alipay")) {
                                searchDTO.setPayType("支付宝");
                            } else if (member.getPayType().equals("mqpay_wxpay")
                                    || member.getPayType().equals("zlianpay_wxpay")
                                    || member.getPayType().equals("yungouos_wxpay")
                                    || member.getPayType().equals("xunhupay_wxpay")
                                    || member.getPayType().equals("jiepay_wxpay")
                                    || member.getPayType().equals("payjs_wxpay")
                                    || member.getPayType().equals("yunfu_wxpay")
                                    || member.getPayType().equals("wxpay")
                                    || member.getPayType().equals("wxpay_h5")) {
                                searchDTO.setPayType("微信");
                            }
                            if (member.getStatus() == 1) {
                                searchDTO.setStatus("付款成功");
                            } else if (member.getStatus() == 2) {
                                searchDTO.setStatus("待发货");
                            } else if (member.getStatus() == 3) {
                                searchDTO.setStatus("已发货");
                            } else {
                                searchDTO.setStatus("未付款");
                            }

                            searchDTO.setMember(member.getMember());
                            ordersList.add(searchDTO);
                        }
                        model.addAttribute("ordersList", JSON.toJSONString(ordersList));
                    } else {
                        List<SearchDTO> ordersList = new ArrayList<>();
                        AtomicInteger index = new AtomicInteger(0);
                        Orders member = ordersService.getOne(new QueryWrapper<Orders>().eq("member", cookieValue));

                        if (!ObjectUtils.isEmpty(member)) {
                            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");//设置日期格式
                            String date = df.format(member.getCreateTime());// new Date()为获取当前系统时间，也可使用当前时间戳

                            SearchDTO searchDTO = new SearchDTO();
                            searchDTO.setId(member.getId().toString());
                            Integer andIncrement = (Integer) index.getAndIncrement();
                            searchDTO.setAndIncrement(andIncrement.toString());
                            searchDTO.setCreateTime(date);
                            searchDTO.setMoney(member.getMoney().toString());
                            if (member.getPayType().equals("mqpay_alipay")
                                    || member.getPayType().equals("zlianpay_alipay")
                                    || member.getPayType().equals("yungouos_alipay")
                                    || member.getPayType().equals("xunhupay_alipay")
                                    || member.getPayType().equals("jiepay_alipay")
                                    || member.getPayType().equals("payjs_alipay")
                                    || member.getPayType().equals("yunfu_alipay")
                                    || member.getPayType().equals("alipay")) {
                                searchDTO.setPayType("支付宝");
                            } else if (member.getPayType().equals("mqpay_wxpay")
                                    || member.getPayType().equals("zlianpay_wxpay")
                                    || member.getPayType().equals("yungouos_wxpay")
                                    || member.getPayType().equals("xunhupay_wxpay")
                                    || member.getPayType().equals("jiepay_wxpay")
                                    || member.getPayType().equals("payjs_wxpay")
                                    || member.getPayType().equals("yunfu_wxpay")
                                    || member.getPayType().equals("wxpay")
                                    || member.getPayType().equals("wxpay_h5")) {
                                searchDTO.setPayType("微信");
                            }
                            if (member.getStatus() == 1) {
                                searchDTO.setStatus("付款成功");
                            } else if (member.getStatus() == 2) {
                                searchDTO.setStatus("待发货");
                            } else if (member.getStatus() == 3) {
                                searchDTO.setStatus("已发货");
                            } else {
                                searchDTO.setStatus("未付款");
                            }
                            searchDTO.setMember(member.getMember());
                            ordersList.add(searchDTO);
                        }

                        model.addAttribute("ordersList", JSON.toJSONString(ordersList));
                    }
                    break;
                } else {
                    List<SearchDTO> ordersList = new ArrayList<>();
                    model.addAttribute("ordersList", JSON.toJSONString(ordersList));
                }
            }
        } else {
            List<SearchDTO> ordersList = new ArrayList<>();
            model.addAttribute("ordersList", JSON.toJSONString(ordersList));
        }

        Website website = websiteService.getById(1);
        model.addAttribute("website", website);

        ShopSettings shopSettings = shopSettingsService.getById(1);
        model.addAttribute("isBackground", shopSettings.getIsBackground());

        Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
        return "theme/" + theme.getDriver() + "/search.html";
    }

    @RequestMapping("/search/order/{order}")
    public String searchOrder(Model model, @PathVariable("order") String order) {
        Orders member = ordersService.getOne(new QueryWrapper<Orders>().eq("member", order));
        Products products = productsService.getById(member.getProductId());
        if (!StringUtils.isEmpty(products.getIsPassword())) {
            if (products.getIsPassword() == 1) {

                Website website = websiteService.getById(1);
                model.addAttribute("website", website);

                ShopSettings shopSettings = shopSettingsService.getById(1);
                model.addAttribute("isBackground", shopSettings.getIsBackground());

                model.addAttribute("orderId", member.getId());
                model.addAttribute("member", member.getMember());
                Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
                return "theme/" + theme.getDriver() + "/orderPass.html";
            }
        }
        Classifys classifys = classifysService.getById(products.getClassifyId());
        List<OrderCard> ordersList = orderCardService.list(new QueryWrapper<OrderCard>().eq("order_id", member.getId()));
        List<String> cardsList = new ArrayList<>();
        for (OrderCard orderCard : ordersList) {
            Cards cards = cardsService.getById(orderCard.getCardId());
            String cardInfo = cards.getCardInfo();
            StringBuilder builder = new StringBuilder();
            if (products.getShipType() == 0) {
                if (cardInfo.contains(" ")) {
                    String[] split = cardInfo.split(" ");
                    builder.append("卡号：").append(split[0]).append(" ").append("卡密：").append(split[1]).append("\n");
                    cardsList.add(builder.toString());
                } else {
                    builder.append("卡密：").append(cardInfo).append("\n");
                    cardsList.add(builder.toString());
                }
            } else {
                builder.append(builder);
                cardsList.add(builder.toString());
            }
        }
        OrdersVo ordersVo = new OrdersVo();
        BeanUtils.copyProperties(member, ordersVo);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");//设置日期格式
        if (member.getPayTime() != null) {
            String date = df.format(member.getPayTime());// new Date()为获取当前系统时间，也可使用当前时间戳
            ordersVo.setPayTime(date);
        } else {
            ordersVo.setPayTime(null);
        }
        ordersVo.setMoney(member.getMoney().toString());
        /**
         * 发货模式
         */
        ordersVo.setShipType(products.getShipType());
        Website website = websiteService.getById(1);
        model.addAttribute("website", website);
        model.addAttribute("cardsList", cardsList); // 订单
        model.addAttribute("orders", ordersVo); // 订单
        model.addAttribute("goods", products);  // 商品
        model.addAttribute("classify", classifys);  // 分类
        ShopSettings shopSettings = shopSettingsService.getById(1);
        model.addAttribute("isBackground", shopSettings.getIsBackground());
        Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
        return "theme/" + theme.getDriver() + "/order.html";
    }

    /**
     * 支付状态
     *
     * @param model
     * @return
     */
    @RequestMapping("/pay/state/{payId}")
    public String payState(Model model, @PathVariable("payId") String payId) {
        Orders orders = ordersService.getOne(new QueryWrapper<Orders>().eq("member", payId));
        model.addAttribute("orderId", orders.getId());
        model.addAttribute("ordersMember", orders.getMember());

        Website website = websiteService.getById(1);
        model.addAttribute("website", website);

        ShopSettings shopSettings = shopSettingsService.getById(1);
        model.addAttribute("isBackground", shopSettings.getIsBackground());

        Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
        return "theme/" + theme.getDriver() + "/payState.html";
    }

    @ResponseBody
    @GetMapping("/getProductSearchList")
    public JsonResult getProductSearchList(Integer classifyId, String content) {
        List<Products> productsList = productsService.list(new QueryWrapper<Products>().eq("classify_id", classifyId).eq("status", 1).like("name", content));
        List<ProductsVo> productsVoList = productsList.stream().map((products) -> {
            ProductsVo productsVo = new ProductsVo();
            BeanUtils.copyProperties(products, productsVo);
            int count = cardsService.count(new QueryWrapper<Cards>().eq("product_id", products.getId()).eq("status", 0));
            productsVo.setCardMember(count);
            productsVo.setPrice(products.getPrice().toString());
            return productsVo;
        }).collect(Collectors.toList());
        return JsonResult.ok("查询成功！").setData(productsVoList);
    }
}
