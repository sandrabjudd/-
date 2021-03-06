package cn.zlianpay.orders.controller;

import cn.zlianpay.carmi.entity.Cards;
import cn.zlianpay.carmi.entity.OrderCard;
import cn.zlianpay.carmi.service.CardsService;
import cn.zlianpay.carmi.service.OrderCardService;
import cn.zlianpay.common.core.utils.FormCheckUtil;
import cn.zlianpay.common.core.web.*;
import cn.zlianpay.orders.vo.OrderVos;
import cn.zlianpay.reception.controller.NotifyController;
import cn.zlianpay.reception.dto.SearchDTO;
import cn.zlianpay.settings.entity.ShopSettings;
import cn.zlianpay.settings.service.ShopSettingsService;
import cn.zlianpay.website.entity.Website;
import cn.zlianpay.website.service.WebsiteService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.zlianpay.common.core.utils.RequestParamsUtil;
import cn.zlianpay.common.core.web.*;
import cn.zlianpay.common.core.annotation.OperLog;
import cn.zlianpay.common.system.service.EmailService;
import cn.zlianpay.orders.entity.Orders;
import cn.zlianpay.orders.service.OrdersService;
import cn.zlianpay.orders.vo.OrdersVo;
import cn.zlianpay.products.entity.Products;
import cn.zlianpay.products.service.ProductsService;
import cn.zlianpay.settings.service.PaysService;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ???????????????
 * Created by Panyoujie on 2021-03-29 16:24:28
 */
@Controller
@Transactional
@RequestMapping("/orders/orders")
public class OrdersController extends BaseController {

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private ProductsService productsService;

    @Autowired
    private CardsService cardsService;

    @Autowired
    private OrderCardService orderCardService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private WebsiteService websiteService;

    @Autowired
    private ShopSettingsService shopSettingsService;

    @RequiresPermissions("orders:orders:view")
    @RequestMapping()
    public String view() {
        return "orders/orders.html";
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:list")
    @OperLog(value = "???????????????", desc = "????????????")
    @ResponseBody
    @RequestMapping("/page")
    public JsonResult page(HttpServletRequest request) {
        PageParam<Orders> pageParam = new PageParam<>(request);
        pageParam.setDefaultOrder(null, new String[]{"create_time"});
        PageResult<Orders> ordersPageResult = ordersService.listPage(pageParam);
        List<OrdersVo> ordersVoList = ordersPageResult.getData().stream().map((orders) -> {
            OrdersVo ordersVo = new OrdersVo();
            BeanUtils.copyProperties(orders, ordersVo);

            Products products = productsService.getById(orders.getProductId());
            ordersVo.setProductName(products.getName());

            List<OrderCard> orderCardList = orderCardService.list(new QueryWrapper<OrderCard>().eq("order_id", orders.getId()));
            List<Cards> list = new ArrayList<>();
            for (OrderCard orderCard : orderCardList) {
                Cards cards = cardsService.getById(orderCard.getCardId());
                list.add(cards);
            }
            ordersVo.setCardInfo(list);

            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");//??????????????????

            if (orders.getPayTime() != null) {
                String date = df.format(orders.getPayTime());// new Date()?????????????????????????????????????????????????????????
                ordersVo.setPayTime(date);
            } else {
                ordersVo.setPayTime(null);
            }

            ordersVo.setMoney(orders.getMoney().toString());

            // ????????????
            ordersVo.setShipType(products.getShipType());

            return ordersVo;
        }).collect(Collectors.toList());

        BigDecimal totalAmount = new BigDecimal(0.00);
        for (OrdersVo ordersVo : ordersVoList) {
            if (ordersVo.getStatus() >= 1) {
                totalAmount = totalAmount.add(new BigDecimal(ordersVo.getMoney())).setScale(2, BigDecimal.ROUND_HALF_DOWN);
            }
        }
        Map<String, String> totalRow = new HashMap<>();
        totalRow.put("money", totalAmount.toString());

        return JsonResult.ok("???????????????").put("totalRow", totalRow).put("count", ordersPageResult.getCount()).setData(ordersVoList);
    }

    /**
     * ????????????
     */
    @OperLog(value = "??????", desc = "????????????")
    @ResponseBody
    @RequestMapping("/pageAll")
    public JsonResult pageall(HttpServletRequest request) {
        PageParam<Orders> pageParam = new PageParam<>(request);

        Map parameterMap = RequestParamsUtil.getParameterMap(request);
        String contact = (String) parameterMap.get("contact");
        QueryWrapper<Orders> wrapper = new QueryWrapper<>();
        wrapper.eq("contact", contact)
                .or().eq("email", contact)
                .or().eq("member", contact)
                .or().eq("pay_no", contact)
                .orderByDesc("create_time");

        List<Orders> ordersList = ordersService.page(pageParam, wrapper).getRecords();

        AtomicInteger index = new AtomicInteger(0);
        List<SearchDTO> orderVosList = ordersList.stream().map((orders) -> {

            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");//??????????????????
            String date = df.format(orders.getCreateTime());// new Date()?????????????????????????????????????????????????????????

            SearchDTO searchDTO = new SearchDTO();
            searchDTO.setId(orders.getId().toString());
            Integer andIncrement = (Integer) index.getAndIncrement();
            searchDTO.setAndIncrement(andIncrement.toString());
            searchDTO.setCreateTime(date);
            searchDTO.setMoney(orders.getMoney().toString());
            if (orders.getPayType().equals("mqpay_alipay")
                    || orders.getPayType().equals("zlianpay_alipay")
                    || orders.getPayType().equals("yungouos_alipay")
                    || orders.getPayType().equals("xunhupay_alipay")
                    || orders.getPayType().equals("jiepay_alipay")
                    || orders.getPayType().equals("payjs_alipay")
                    || orders.getPayType().equals("yunfu_alipay")
                    || orders.getPayType().equals("alipay")) {
                searchDTO.setPayType("?????????");
            } else if (orders.getPayType().equals("mqpay_wxpay")
                    || orders.getPayType().equals("zlianpay_wxpay")
                    || orders.getPayType().equals("yungouos_wxpay")
                    || orders.getPayType().equals("xunhupay_wxpay")
                    || orders.getPayType().equals("jiepay_wxpay")
                    || orders.getPayType().equals("payjs_wxpay")
                    || orders.getPayType().equals("yunfu_wxpay")
                    || orders.getPayType().equals("wxpay")
                    || orders.getPayType().equals("wxpay_h5")) {
                searchDTO.setPayType("??????");
            } else if (orders.getPayType().equals("paypal")) {
                searchDTO.setPayType("Paypal");
            }
            if (orders.getStatus() == 1) {
                searchDTO.setStatus("?????????");
            } else if (orders.getStatus() == 2) {
                searchDTO.setStatus("?????????");
            } else if (orders.getStatus() == 3) {
                searchDTO.setStatus("?????????");
            } else {
                searchDTO.setStatus("?????????");
            }

            searchDTO.setMember(orders.getMember());

            return searchDTO;
        }).collect(Collectors.toList());

        return JsonResult.ok("???????????????").setData(orderVosList);
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:list")
    @OperLog(value = "???????????????", desc = "????????????")
    @ResponseBody
    @RequestMapping("/list")
    public JsonResult list(HttpServletRequest request) {
        PageParam<Orders> pageParam = new PageParam<>(request);
        return JsonResult.ok().setData(ordersService.list(pageParam.getOrderWrapper()));
    }

    /**
     * ??????id???????????????
     */
    @RequiresPermissions("orders:orders:list")
    @OperLog(value = "???????????????", desc = "??????id??????")
    @ResponseBody
    @RequestMapping("/get")
    public JsonResult get(Integer id) {
        return JsonResult.ok().setData(ordersService.getById(id));
    }

    /**
     * ???????????????
     */
    @RequiresPermissions("orders:orders:save")
    @OperLog(value = "???????????????", desc = "??????", param = false, result = true)
    @ResponseBody
    @RequestMapping("/save")
    public JsonResult save(Orders orders) {
        if (ordersService.save(orders)) {
            return JsonResult.ok("????????????");
        }
        return JsonResult.error("????????????");
    }

    /**
     * ???????????????
     */
    @RequiresPermissions("orders:orders:update")
    @OperLog(value = "???????????????", desc = "??????", param = false, result = true)
    @ResponseBody
    @RequestMapping("/update")
    public JsonResult update(Orders orders) {
        if (ordersService.updateById(orders)) {
            return JsonResult.ok("????????????");
        }
        return JsonResult.error("????????????");
    }

    /**
     * ???????????????
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "???????????????", desc = "??????", result = true)
    @ResponseBody
    @RequestMapping("/remove")
    public JsonResult remove(Integer id) {
        if (ordersService.removeById(id)) {
            return JsonResult.ok("????????????");
        }
        return JsonResult.error("????????????");
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:save")
    @OperLog(value = "???????????????", desc = "????????????", param = false, result = true)
    @ResponseBody
    @RequestMapping("/saveBatch")
    public JsonResult saveBatch(@RequestBody List<Orders> list) {
        if (ordersService.saveBatch(list)) {
            return JsonResult.ok("????????????");
        }
        return JsonResult.error("????????????");
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:update")
    @OperLog(value = "???????????????", desc = "????????????", result = true)
    @ResponseBody
    @RequestMapping("/updateBatch")
    public JsonResult updateBatch(@RequestBody BatchParam<Orders> batchParam) {
        if (batchParam.update(ordersService, "id")) {
            return JsonResult.ok("????????????");
        }
        return JsonResult.error("????????????");
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "???????????????", desc = "????????????", result = true)
    @ResponseBody
    @RequestMapping("/removeBatch")
    public JsonResult removeBatch(@RequestBody List<Integer> ids) {
        if (ordersService.removeByIds(ids)) {
            return JsonResult.ok("????????????");
        }
        return JsonResult.error("????????????");
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "???????????????", desc = "????????????", result = true)
    @ResponseBody
    @RequestMapping("/clearRemove")
    public JsonResult clearRemove() {
        if (ordersService.clearRemove()) {
            return JsonResult.ok("?????????????????????????????????");
        }
        return JsonResult.error("??????????????????????????????");
    }

    /**
     * ?????????????????????
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "???????????????", desc = "????????????", result = true)
    @ResponseBody
    @RequestMapping("/clearAllRemove")
    public JsonResult clearAllRemove() {
        if (ordersService.clearAllRemove()) {
            return JsonResult.ok("????????????????????????");
        }
        return JsonResult.error("??????????????????????????????");
    }

    /**
     * ???????????????
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "???????????????", desc = "????????????", result = true)
    @ResponseBody
    @RequestMapping("/deleteById")
    public JsonResult deleteById(Integer id) {
        if (ordersService.deleteById(id)) {
            return JsonResult.ok("?????????????????????");
        }
        return JsonResult.error("??????????????????????????????");
    }

    /**
     * @param id       ??????id
     * @param shipInfo ?????????????????????
     * @return
     */
    @OperLog(value = "??????????????????", desc = "????????????", result = true)
    @RequiresPermissions("orders:orders:update")
    @ResponseBody
    @RequestMapping("/sendShip")
    public JsonResult sendShip(Integer id, String email, String shipInfo) throws MessagingException, IOException {

        /**
         * ????????????
         */
        Orders orders = ordersService.getById(id);
        Products products = productsService.getById(orders.getProductId()); // ?????????????????????

        Cards cards = new Cards();
        cards.setCardInfo(shipInfo);
        cards.setCreatedAt(new Date());
        cards.setProductId(products.getId());
        cards.setStatus(1); // ???????????????
        cards.setUpdatedAt(new Date());

        if (cardsService.save(cards)) {
            OrderCard orderCard = new OrderCard();
            orderCard.setCardId(cards.getId());
            orderCard.setOrderId(orders.getId());
            orderCard.setCreatedAt(new Date());
            orderCardService.save(orderCard); // ????????????
        }

        Orders orders1 = new Orders();
        orders1.setId(orders.getId());
        orders1.setStatus(3);

        if (ordersService.updateById(orders1)) { // ??????????????????
            if (FormCheckUtil.isEmail(email)) {
                Website website = websiteService.getById(1);
                Map<String, Object> map = new HashMap<>();  // ?????????????????????
                map.put("title", website.getWebsiteName());
                map.put("member", orders.getMember());
                map.put("date", new Date());
                map.put("info", shipInfo);
                try {
                    ShopSettings shopSettings = shopSettingsService.getById(1);
                    if (shopSettings.getIsEmail() == 1) {
                        emailService.sendHtmlEmail(website.getWebsiteName() + "????????????", "email/sendShip.html", map, new String[]{email});
                        return JsonResult.ok("???????????????????????????????????????");
                    }
                    return JsonResult.ok("???????????????");
                } catch (AuthenticationFailedException e) {
                    return JsonResult.ok("????????????????????????????????????");
                }
            }
            return JsonResult.ok("????????????????????????????????????");
        }
        return JsonResult.error("???????????????");
    }

    /**
     * ??????????????????
     */
    @OperLog(value = "??????????????????", desc = "????????????", result = true)
    @RequiresPermissions("orders:orders:update")
    @ResponseBody
    @RequestMapping("/status/update")
    public JsonResult updateStates(Integer id, String payNo, Integer productId) {

        Orders member = ordersService.getById(id);
        if (member == null) return JsonResult.error("????????????????????????"); // ????????????????????????

        int count = orderCardService.count(new QueryWrapper<OrderCard>().eq("order_id", member.getId()));
        if (count >= 1) return JsonResult.ok("??????????????????????????????????????????????????????");

        Products products = productsService.getById(productId);
        if (products == null) return JsonResult.error("??????????????????????????????"); // ????????????

        Website website = websiteService.getById(1);
        ShopSettings shopSettings = shopSettingsService.getById(1);

        if (products.getShipType() == 0) { // ?????????????????????
            List<Cards> card = cardsService.getCard(0, products.getId(), member.getNumber());
            if (card == null) return JsonResult.error("????????????????????????????????????");

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
                            return JsonResult.error("?????????????????????????????????????????????");
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
            products1.setInventory(products.getInventory() - 1);
            products1.setSales(products.getSales() + 1);

            if (shopSettings.getIsWxpusher() == 1) {
                Message message = new Message();
                message.setContent(website.getWebsiteName() + "???????????????<br>????????????<span style='color:red;'>" + member.getMember() + "</span><br>???????????????<span>" + products.getName() + "</span><br>???????????????<span>" + member.getMoney() + "</span><br>???????????????<span style='color:green;'>??????</span><br>");
                message.setContentType(Message.CONTENT_TYPE_HTML);
                message.setUid(shopSettings.getWxpushUid());
                message.setAppToken(shopSettings.getAppToken());
                WxPusher.send(message);
            }
            if (shopSettings.getIsEmail() == 1) {
                if (FormCheckUtil.isEmail(member.getEmail())) {
                    try {
                        emailService.sendTextEmail("????????????", "?????????????????????" + member.getMember() + " ?????????????????????????????????????????????", new String[]{member.getEmail()});
                    } catch (Exception e) {
                        return JsonResult.error("?????????????????????????????????????????????");
                    }
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
        orders.setPayNo(payNo);
        orders.setPrice(member.getPrice());
        orders.setMoney(member.getMoney());

        boolean b = ordersService.updateById(orders);// ????????????

        return JsonResult.ok("??????????????????");
    }

}
