# billing-license-service
做一个统一的 Billing &amp; License Service。 国内通过持牌聚合支付接入支付宝、微信、云闪付；国外接入 Paddle、Stripe、PayPal。 所有支付成功后统一生成兑换码；如果购买时带有机器码，则直接签发绑定设备的非对称签名 License。 客户端内置公钥验证 License，激活后离线可用。 服务端用 KMS/HSM 保管私钥，Webhook 统一验签、幂等、对账和发货。
