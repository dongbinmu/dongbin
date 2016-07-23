package org.dtb.alipay.web;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.log4j.Logger;
import org.dtb.alipay.util.AlipayNotify;
import org.dtb.carserver.server.sendMsg.SendMsgService;
import org.dtb.framework.dao.SqlSessionTemplate;
import org.dtb.framework.utils.DateTimeUtils;
import org.dtb.framework.utils.JsonUtils;
import org.dtb.order.entity.OrderInfo;
import org.dtb.pay.entity.PayBean;
import org.dtb.pay.service.PayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/alipay")
public class AlipayController {
	@Autowired
	private PayService payService;
	@Autowired
	private SqlSessionTemplate sqlSessionTemplate;
	@Autowired
	private SendMsgService sendMsgService;
	private Logger log = Logger.getLogger(AlipayController.class);

	@RequestMapping("/payresult")
	public void payresult(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String tradstatus = request.getParameter("trade_status");// 获取交易状态
		String orderno = request.getParameter("out_trade_no");// 获取打车订单号
		String tradeno = request.getParameter("trade_no");// 获取支付宝账单号/流水号
		String amount = request.getParameter("total_fee");// 获取订单总金额
		Map<String, String> prams = new HashMap<String, String>();
		Map<?, ?> rprams = request.getParameterMap();
		for (Iterator<?> iterator = rprams.keySet().iterator(); iterator.hasNext();) {// for循环将传递过来的参数以键值对依次放入map中
			String name = (String) iterator.next();
			String[] values = (String[]) rprams.get(name);// 把迭代器的数据转化成string数组
			String valuestr = "";
			for (int i = 0; i < values.length; i++) {
				valuestr = (i == values.length - 1) ? valuestr + values[i] : valuestr + values[i] + ",";
			}
			prams.put(name, valuestr);// 键值对赋值
		}
		if (AlipayNotify.verify(prams)) {// 支付宝自带的验证方法
			log.info("验证成功");
			if (tradstatus.equalsIgnoreCase("TRADE_SUCCESS") || tradstatus.equalsIgnoreCase("TRADE_FINISHED")) {// 判断支付宝回调的状态参数
				log.info("支付宝端交易完成");
				Map<String, String> map = new HashMap<String, String>();
				map.put("DDBH", orderno);
				map.put("paymentResult", "readytopay");
				PayBean pay = sqlSessionTemplate.selectOne("org.dtb.pay.dao.mappers.payMapper.getPaymentByOrderNo",
						map);
				String actualfee = pay.getTotal_fee();// 获取表中的金额
				String fee = String.valueOf(Double.valueOf(actualfee) / 100);// 表中的金额以分存储，需换算
				
				log.info("--------------------amount"+amount);
				log.info("--------------------fee"+fee);
				
				double d_amount = Double.parseDouble(amount); 
				double d_fee = Double.parseDouble(fee); 
				
				log.info("----------------d_amount:"+d_amount);
				log.info("----------------d_amount:"+d_fee);
				
				if (d_fee == d_amount) { // 换算后的金额与传递过来的金额作对比
					log.info("金额匹配成功");
					pay.setPayType("alipay"); // 支付宝支付
					pay.setFee_type("CNY");// 支付金额类型
					pay.setTrade_state("SUCCESS");// 支付成功
					pay.setTransaction_id(tradeno); // 支付宝流水号
					pay.setTrade_type("APP");// 交易类型
					pay.setTime_end(DateTimeUtils.dateToString(new Date(), DateTimeUtils.sdfyyyyMMddHHmmss));
					sqlSessionTemplate.update("org.dtb.pay.dao.mappers.payMapper.updateAli", pay);// 更新支付信息表payment
					log.info("支付表更新成功");
					OrderInfo orderInfo = new OrderInfo();
					orderInfo.setOrderNo(orderno);
					orderInfo.setOrderStatus("05");// 改变订单状态
					orderInfo.setUpdatetime(new Date());
					orderInfo.setPayMentModel("alipay");
					sqlSessionTemplate.update("org.dtb.order.mappers.orderInfoMapper.update", orderInfo);// 更新订单信息order
					Double value = Double.valueOf(pay.getTotal_fee());
					BigDecimal orderprice = new BigDecimal(value / 100).setScale(1, BigDecimal.ROUND_UP);
					sendMsgService.paymentSms(orderno, orderprice.toString(), "1");
					log.info("添加进订单信息");
					PrintWriter out = response.getWriter();
					out.write("success");
				} else {
					log.info("金额匹配不成功");
				}
			} else {
				log.info("支付宝交易端交易未完成");// 交易状态不正确
			}
		} else {
			log.info("验证失败");
		}
	}

	@RequestMapping("/addpayment")
	public void addpayment(String orderno,String fee,HttpServletRequest request, HttpServletResponse response) throws Exception {
		Map<String, String> map1 = new HashMap<String, String>();
		map1.put("DDBH", orderno);
		map1.put("paymentResult", "readytopay");
		PayBean pay = sqlSessionTemplate.selectOne("org.dtb.pay.dao.mappers.payMapper.getPaymentByOrderNo", map1);
		if (pay == null) {
			PayBean payBean = new PayBean();
			payBean.setUUID(UUID.randomUUID().toString().replace("-", ""));
			payBean.setOut_trade_no(orderno);
			payBean.setTotal_fee(fee == null ? "0" : String.valueOf(Double.valueOf(fee) * 100));
			payBean.setTrade_state("readytopay");
			payBean.setTime_end(DateTimeUtils.dateToString(new Date(), DateTimeUtils.sdfyyyyMMddHHmmss));
			Map<String, String> map = new HashMap<String, String>();
			try {
				payService.addPayInfo(payBean);
				log.info("支付前插入表成功");
				map.put("status", "1");
				map.put("message", "支付前插入表成功");
			} catch (Exception e) {
				e.printStackTrace();
				log.info("支付前插入表失败");
				map.put("status", "2");
				map.put("message", "支付前插入表失败");
			}
			JsonUtils.responseJson(response, map);
		} else {
			pay.setTotal_fee(fee == null ? "0" : String.valueOf(Double.valueOf(fee) * 100));
			pay.setTime_end(DateTimeUtils.dateToString(new Date(), DateTimeUtils.sdfyyyyMMddHHmmss));
			payService.resetPayInfo(pay, null);
			
			Map<String, String> map2 = new HashMap<String, String>();
			map2.put("status", "2");
			map2.put("message", "执行支付单状态更新");
			JsonUtils.responseJson(response, map2);
		}
	}

}
