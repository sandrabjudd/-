package cn.zlianpay.reception.controller;

import cn.zlianpay.carmi.entity.Cards;
import cn.zlianpay.carmi.entity.OrderCard;
import cn.zlianpay.carmi.service.CardsService;
import cn.zlianpay.carmi.service.OrderCardService;
import cn.zlianpay.common.core.pays.alipay.SendAlipay;
import cn.zlianpay.common.core.pays.jiepay.JiepaySend;
import cn.zlianpay.common.core.pays.payjs.sendPayjs;
import cn.zlianpay.common.core.pays.paypal.PaypalSend;
import cn.zlianpay.common.core.pays.paypal.config.PaypalPaymentIntent;
import cn.zlianpay.common.core.pays.paypal.config.PaypalPaymentMethod;
import cn.zlianpay.common.core.pays.wxpay.SendWxPay;
import cn.zlianpay.common.core.pays.xunhupay.PayUtils;
import cn.zlianpay.common.core.pays.yunfupay.SendYunfu;
import cn.zlianpay.common.core.pays.zlianpay.ZlianPay;
import cn.zlianpay.common.core.utils.FormCheckUtil;
import cn.zlianpay.common.core.utils.UserAgentGetter;
import cn.zlianpay.common.core.web.BaseController;
import cn.zlianpay.common.core.web.JsonResult;
import cn.zlianpay.common.system.service.EmailService;
import cn.zlianpay.settings.entity.Coupon;
import cn.zlianpay.settings.entity.ShopSettings;
import cn.zlianpay.settings.service.CouponService;
import cn.zlianpay.settings.service.ShopSettingsService;
import cn.zlianpay.theme.entity.Theme;
import cn.zlianpay.theme.service.ThemeService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.zlianpay.carmi.entity.Cards;
import cn.zlianpay.carmi.service.CardsService;
import cn.zlianpay.common.core.annotation.OperLog;
import cn.zlianpay.common.core.pays.mqpay.mqPay;
import cn.zlianpay.common.core.pays.yungouos.YunGouosConfig;
import cn.zlianpay.common.core.utils.StringUtil;
import cn.zlianpay.orders.entity.Orders;
import cn.zlianpay.orders.service.OrdersService;
import cn.zlianpay.products.entity.Products;
import cn.zlianpay.products.service.ProductsService;
import cn.zlianpay.settings.entity.Pays;
import cn.zlianpay.settings.service.PaysService;
import cn.zlianpay.website.entity.Website;
import cn.zlianpay.website.service.WebsiteService;
import com.paypal.api.payments.Links;
import com.paypal.api.payments.Payment;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mobile.device.Device;
import org.springframework.mobile.device.DevicePlatform;
import org.springframework.mobile.device.DeviceUtils;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.*;

@Controller
@Transactional
public class OrderController extends BaseController {

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private PaysService paysService;

    @Autowired
    private ProductsService productsService;

    @Autowired
    private CardsService cardsService;

    @Autowired
    private WebsiteService websiteService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private ThemeService themeService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private OrderCardService orderCardService;

    @Autowired
    private ShopSettingsService shopSettingsService;

    /**
     * ??????
     */
    @ResponseBody
    @RequestMapping("/buy")
    public JsonResult save(Integer goodsId, Integer number, String contact, String coupon, String payType, String password, HttpServletResponse response, HttpServletRequest request) {

        if (StringUtils.isEmpty(goodsId)) {
            return JsonResult.error("??????????????????");
        } else if (StringUtils.isEmpty(contact)) {
            return JsonResult.error("???????????????????????????");
        } else if (StringUtils.isEmpty(number)) {
            return JsonResult.error("?????????????????????????????????0");
        } else if (StringUtils.isEmpty(payType)) {
            return JsonResult.error("????????????????????????");
        }

        Products products = productsService.getById(goodsId);
        if (products.getRestricts() >= 1) { // ?????????????????????
            if (number > products.getRestricts()) { // ????????????
                return JsonResult.error("???????????????????????????" + products.getRestricts() + "???????????????????????????");
            }
        }

        if (!StringUtils.isEmpty(products.getIsPassword())) {
            if (products.getIsPassword() == 1) {
                if (StringUtils.isEmpty(password)) {
                    return JsonResult.error("????????????????????????????????????");
                }
            }
        }

        if (products.getShipType() == 0) { // ??????????????????
            int count = cardsService.count(new QueryWrapper<Cards>().eq("product_id", goodsId).eq("status", 0));
            if (count == 0) {
                return JsonResult.error("?????????????????????????????????????????????");
            } else if (number > count) {
                return JsonResult.error("???????????????????????????????????????????????????");
            }
        } else { // ??????????????????
            if (products.getInventory() == 0) {
                return JsonResult.error("?????????????????????????????????????????????");
            } else if (number > products.getInventory()) {
                return JsonResult.error("???????????????????????????????????????????????????");
            }
        }

        try {
            Integer couponId = null;
            if (!StringUtils.isEmpty(coupon)) {
                QueryWrapper<Coupon> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("product_id", goodsId) // ??????id
                        .eq("coupon", coupon) // ???????????????
                        .eq("status", 0); // ???????????????

                Coupon coupon1 = couponService.getOne(queryWrapper);
                if (!ObjectUtils.isEmpty(coupon1)) { // ?????? coupon1 ???????????????
                    /**
                     * ??????????????? entity
                     */
                    couponId = coupon1.getId();
                } else {
                    /**
                     * ???????????????????????????????????????????????????
                     */
                    return JsonResult.error("???????????????????????????????????????????????????????????????????????????????????????");
                }
            }
            Map<String, String> buy = ordersService.buy(goodsId, number, contact, couponId, payType, password, request);
            Cookie[] cookies = request.getCookies();
            if (ObjectUtils.isEmpty(cookies)) {
                /**
                 * ?????? cookie
                 * ?????????????????????????????????
                 */
                Cookie cookie1 = new Cookie("BROWSER_ORDERS_CACHE", buy.get("member"));
                cookie1.setMaxAge(24 * 60 * 60); // 1?????????
                // ???cookie????????????response??????
                response.addCookie(cookie1);
            } else {
                for (Cookie cookie : cookies) {
                    String cookieName = cookie.getName();
                    if ("BROWSER_ORDERS_CACHE".equals(cookieName)) {
                        String cookieValue = cookie.getValue();
                        /**
                         * ?????? cookie
                         * ?????????????????????????????????
                         */
                        Cookie cookie1 = new Cookie("BROWSER_ORDERS_CACHE", cookieValue + "=" + buy.get("member"));
                        cookie1.setMaxAge(24 * 60 * 60); // 1?????????
                        // ???cookie????????????response??????
                        response.addCookie(cookie1);
                        break;
                    } else {
                        /**
                         * ?????? cookie
                         * ?????????????????????????????????
                         */
                        Cookie cookie1 = new Cookie("BROWSER_ORDERS_CACHE", buy.get("member"));
                        cookie1.setMaxAge(24 * 60 * 60); // 1?????????
                        // ???cookie????????????response??????
                        response.addCookie(cookie1);
                    }
                }
            }

            return JsonResult.ok("?????????????????????").setCode(200).setData(buy);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonResult.error("??????????????????");
        }
    }

    @OperLog(value = "??????", desc = "????????????")
    @RequestMapping(value = "/pay/{member}", produces = "text/html")
    public String pay(Model model, @PathVariable("member") String member, HttpServletResponse response, HttpServletRequest request) throws IOException, NoSuchAlgorithmException {
        Orders orders = ordersService.selectByMember(member);
        Products products = productsService.getById(orders.getProductId());

        String productDescription = products.getId().toString(); // ????????????
        String ordersMember = orders.getMember(); // ?????????
        String goodsName = products.getName(); // ????????????
        String cloudPayid = orders.getCloudPayid();
        String price = orders.getMoney().toString();
        UserAgentGetter agentGetter = new UserAgentGetter(request);
        Pays pays = paysService.getOne(new QueryWrapper<Pays>().eq("driver", orders.getPayType()).eq("enabled", 1));

        if (price.equals("0.00")) { // 0????????? ??????????????????
            long time = new Date().getTime();
            String toString = Long.toString(time);
            boolean big = returnBig(price, price, orders.getMember(), toString, productDescription);
            if (big) {
                response.sendRedirect("/search/order/" + orders.getMember());
                return null;
            }
        }

        if (orders.getPayType().equals("mqpay_alipay")) {
            String createMqPay = mqPay.sendCreateMqPay(pays, price, ordersMember, cloudPayid, productDescription);
            response.sendRedirect(createMqPay);
            return null;
        } else if (orders.getPayType().equals("mqpay_wxpay")) {
            String createMqPay = mqPay.sendCreateMqPay(pays, price, ordersMember, cloudPayid, productDescription);
            response.sendRedirect(createMqPay);
            return null;
        } else if (orders.getPayType().equals("zlianpay_wxpay") || orders.getPayType().equals("zlianpay_alipay")) {
            String url = ZlianPay.zlianSendPay(pays, price, ordersMember, productDescription);
            response.sendRedirect(url);
            return null;
        } else if (orders.getPayType().equals("yungouos_wxpay") || orders.getPayType().equals("yungouos_alipay")) {
            String gouos = "";
            if (orders.getPayType().equals("yungouos_wxpay")) {
                model.addAttribute("type", 1);
                gouos = YunGouosConfig.yunGouosWxPay(pays, price, ordersMember, goodsName, productDescription);
            } else if (orders.getPayType().equals("yungouos_alipay")) {
                model.addAttribute("type", 2);
                gouos = YunGouosConfig.yunGouosAliPay(pays, price, ordersMember, goodsName, productDescription);
            }

            model.addAttribute("goodsName", goodsName);
            model.addAttribute("price", price);
            model.addAttribute("ordersMember", ordersMember);
            model.addAttribute("result", JSON.toJSONString(gouos));
            model.addAttribute("orderId", orders.getId());

            Website website = websiteService.getById(1);
            model.addAttribute("website", website);

            ShopSettings shopSettings = shopSettingsService.getById(1);
            model.addAttribute("isBackground", shopSettings.getIsBackground());

            Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
            return "theme/" + theme.getDriver() + "/yunpay.html";
        } else if (orders.getPayType().equals("xunhupay_wxpay") || orders.getPayType().equals("xunhupay_alipay")) {
            if (orders.getPayType().equals("xunhupay_wxpay")) {
                model.addAttribute("type", 1);
            } else if (orders.getPayType().equals("xunhupay_alipay")) {
                model.addAttribute("type", 2);
            }

            Map pay = PayUtils.pay(getWebName(), pays, goodsName, price, ordersMember, productDescription);
            model.addAttribute("goodsName", goodsName);
            model.addAttribute("price", price);
            model.addAttribute("ordersMember", ordersMember);
            model.addAttribute("result", pay.get("url_qrcode"));
            model.addAttribute("wap", pay.get("url1"));
            model.addAttribute("orderId", orders.getId());

            Website website = websiteService.getById(1);
            model.addAttribute("website", website);

            ShopSettings shopSettings = shopSettingsService.getById(1);
            model.addAttribute("isBackground", shopSettings.getIsBackground());

            Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
            return "theme/" + theme.getDriver() + "/xunhupay.html";
        } else if (orders.getPayType().equals("jiepay_wxpay") || orders.getPayType().equals("jiepay_alipay")) {
            String payUtils = JiepaySend.jiePayUtils(pays, price, ordersMember, productDescription);
            response.sendRedirect(payUtils);
            return null;
        } else if (orders.getPayType().equals("payjs_wxpay") || orders.getPayType().equals("payjs_alipay")) {
            String payjs = "";
            if (orders.getPayType().equals("payjs_wxpay")) {
                model.addAttribute("type", 1);
                payjs = sendPayjs.pay(pays, price, ordersMember, goodsName, productDescription);
            } else if (orders.getPayType().equals("payjs_alipay")) {
                model.addAttribute("type", 2);
                payjs = sendPayjs.pay(pays, price, ordersMember, goodsName, productDescription);
            }

            model.addAttribute("goodsName", goodsName);
            model.addAttribute("price", price);
            model.addAttribute("ordersMember", ordersMember);
            model.addAttribute("result", JSON.toJSONString(payjs));
            model.addAttribute("orderId", orders.getId());

            Website website = websiteService.getById(1);
            model.addAttribute("website", website);

            ShopSettings shopSettings = shopSettingsService.getById(1);
            model.addAttribute("isBackground", shopSettings.getIsBackground());

            Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
            return "theme/" + theme.getDriver() + "/yunpay.html";
        } else if (orders.getPayType().equals("yunfu_wxpay") || orders.getPayType().equals("yunfu_alipay")) {
            String yunfu = "";

            if (orders.getPayType().equals("yunfu_wxpay")) {
                model.addAttribute("type", 1);
                yunfu = SendYunfu.pay(pays, price, ordersMember, goodsName, productDescription, agentGetter.getIp());
            } else if (orders.getPayType().equals("yunfu_alipay")) {
                model.addAttribute("type", 2);
                yunfu = SendYunfu.pay(pays, price, ordersMember, goodsName, productDescription, agentGetter.getIp());
            }

            model.addAttribute("goodsName", goodsName);
            model.addAttribute("price", price);
            model.addAttribute("ordersMember", ordersMember);
            model.addAttribute("result", JSON.toJSONString(yunfu));
            model.addAttribute("orderId", orders.getId());

            Website website = websiteService.getById(1);
            model.addAttribute("website", website);

            ShopSettings shopSettings = shopSettingsService.getById(1);
            model.addAttribute("isBackground", shopSettings.getIsBackground());

            Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
            return "theme/" + theme.getDriver() + "/yunpay.html";
        } else if (pays.getDriver().equals("wxpay")) { // ??????????????????
            String pay = SendWxPay.payNattve(pays, price, ordersMember, goodsName, productDescription, agentGetter.getIp());
            model.addAttribute("type", 1); // ????????????
            model.addAttribute("goodsName", goodsName);
            model.addAttribute("price", price);
            model.addAttribute("ordersMember", ordersMember);
            model.addAttribute("result", JSON.toJSONString(pay));
            model.addAttribute("orderId", orders.getId());
            Website website = websiteService.getById(1);
            model.addAttribute("website", website);
            ShopSettings shopSettings = shopSettingsService.getById(1);
            model.addAttribute("isBackground", shopSettings.getIsBackground());
            Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
            return "theme/" + theme.getDriver() + "/yunpay.html";
        } else if (pays.getDriver().equals("wxpay_h5")) {
            String pay = SendWxPay.payMweb(pays, price, ordersMember, goodsName, productDescription, agentGetter.getIp());
            response.sendRedirect(pay);
            return null;
        } else if (pays.getDriver().equals("alipay")) {
            String pay = SendAlipay.pay(pays, price, ordersMember, goodsName, productDescription, request);
            model.addAttribute("type", 2); // ??????????????????
            model.addAttribute("goodsName", goodsName);
            model.addAttribute("price", price);
            model.addAttribute("ordersMember", ordersMember);
            model.addAttribute("result", JSON.toJSONString(pay));
            model.addAttribute("orderId", orders.getId());

            Website website = websiteService.getById(1);
            model.addAttribute("website", website);

            ShopSettings shopSettings = shopSettingsService.getById(1);
            model.addAttribute("isBackground", shopSettings.getIsBackground());

            Theme theme = themeService.getOne(new QueryWrapper<Theme>().eq("enable", 1));
            return "theme/" + theme.getDriver() + "/yunpay.html";
        } else if (pays.getDriver().equals("paypal")) {
            try {
                Payment payment = PaypalSend.createPayment(pays, price, "USD", PaypalPaymentMethod.paypal, PaypalPaymentIntent.sale, ordersMember);
                for (Links links : payment.getLinks()) {
                    System.out.println(links.toString());
                    if (links.getRel().equals("approval_url")) {
                        System.out.println(links.getHref());
                        return "redirect:" + links.getHref();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
        return null;
    }

    /**
     * ????????????
     * @param money ???????????????
     * @param price ????????????
     * @param payId ?????????
     * @param pay_no ?????????
     * @param param ???????????????
     * @return this
     */
    private boolean returnBig(String money, String price, String payId, String pay_no, String param) {

        /**
         * ?????????????????????
         */
        Orders member = ordersService.getOne(new QueryWrapper<Orders>().eq("member", payId));
        if (member == null) return false; // ????????????????????????

        int count = orderCardService.count(new QueryWrapper<OrderCard>().eq("order_id", member.getId()));
        if (count >= 1)  return true;

        Products products = productsService.getById(param);
        if (products == null) return false; // ????????????

        Website website = websiteService.getById(1);
        ShopSettings shopSettings = shopSettingsService.getById(1);

        if (products.getShipType() == 0) { // ?????????????????????
            List<Cards> card = cardsService.getCard(0, products.getId(), member.getNumber());
            if (card == null) return false;

            List<OrderCard> cardList = new ArrayList<>();
            StringBuilder stringBuilder = new StringBuilder();
            for (Cards cards : card) {
                OrderCard orderCard = new OrderCard();
                orderCard.setCardId(cards.getId());
                orderCard.setOrderId(member.getId());
                orderCard.setCreatedAt(new Date());
                cardList.add(orderCard);

                Cards cards1 = new Cards();
                cards1.setId(cards.getId());
                cards1.setStatus(1);
                cards1.setUpdatedAt(new Date());

                if (cards.getCardInfo().contains(" ")) {
                    String[] split = cards.getCardInfo().split(" ");
                    stringBuilder.append("?????????").append(split[0]).append(" ").append("?????????").append(split[1]).append("\n");
                } else {
                    stringBuilder.append("?????????").append(cards.getCardInfo()).append("\n");
                }
                // ?????????????????????
                cardsService.updateById(cards1);
            }

            if (shopSettings.getIsWxpusher() == 1) {
                Message message = new Message();
                message.setContent(website.getWebsiteName() + "???????????????<br>????????????<span style='color:red;'>" + member.getMember() + "</span><br>???????????????<span>" + products.getName() + "</span><br>???????????????<span>" + member.getNumber() + "</span><br>???????????????<span>"+ member.getMoney() +"</span><br>???????????????<span style='color:green;'>??????</span><br>");
                message.setContentType(Message.CONTENT_TYPE_HTML);
                message.setUid(shopSettings.getWxpushUid());
                message.setAppToken(shopSettings.getAppToken());
                WxPusher.send(message);
            }

            if (shopSettings.getIsEmail() == 1) {
                if (!StringUtils.isEmpty(member.getEmail())) {
                    if (FormCheckUtil.isEmail(member.getEmail())) {
                        Map<String, Object> map = new HashMap<>();  // ?????????????????????
                        map.put("title", website.getWebsiteName());
                        map.put("member", member.getMember());
                        map.put("date", new Date());
                        map.put("info", stringBuilder.toString());
                        try {
                            emailService.sendHtmlEmail(website.getWebsiteName() + "????????????", "email/sendShip.html", map, new String[]{member.getEmail()});
                            // emailService.sendTextEmail("??????????????????", "?????????????????????" + member.getMember() + "  ???????????????" + cards.getCardInfo(), new String[]{member.getEmail()});
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            /**
             * ????????????
             */
            orderCardService.saveBatch(cardList);

        } else { // ??????????????????
            Products products1 = new Products();
            products1.setId(products.getId());
            products1.setInventory(products.getInventory() - member.getNumber());
            products1.setSales(products.getSales() + member.getNumber());

            if (shopSettings.getIsWxpusher() == 1) {
                Message message = new Message();
                message.setContent(website.getWebsiteName() + "???????????????<br>????????????<span style='color:red;'>" + member.getMember() + "</span><br>???????????????<span>" + products.getName() + "</span><br>???????????????<span>" + member.getNumber() + "</span><br>???????????????<span>"+ member.getMoney() +"</span><br>???????????????<span style='color:green;'>??????</span><br>");
                message.setContentType(Message.CONTENT_TYPE_HTML);
                message.setUid(shopSettings.getWxpushUid());
                message.setAppToken(shopSettings.getAppToken());
                WxPusher.send(message);
            }

            if (shopSettings.getIsEmail() == 1) {
                if (FormCheckUtil.isEmail(member.getEmail())) {
                    emailService.sendTextEmail(website.getWebsiteName() + " ????????????", "?????????????????????" + member.getMember() + "  ?????????????????????????????????????????????", new String[]{member.getEmail()});
                }
            }
            productsService.updateById(products1);
        }

        /**
         * ????????????
         */
        Orders orders = new Orders();
        orders.setId(member.getId());

        if (products.getShipType() == 0) {
            orders.setStatus(1); // ???????????????
        } else {
            orders.setStatus(2); // ?????????????????? ????????????
        }

        orders.setPayTime(new Date());
        orders.setPayNo(pay_no);
        orders.setPrice(new BigDecimal(price));
        orders.setMoney(new BigDecimal(money));
        boolean b = ordersService.updateById(orders);// ????????????
        return b;
    }

}
