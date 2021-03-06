# SOA EShop 
介绍请参见http://blog.csdn.net/songxinjianqwe/article/details/78923482
## Dubbo + TCC 分布式事务

## 业务拆分
用户子系统：
- 用户模块：user+mail,涉及user,role,mail,mail_text,balance表
- 产品模块：product,涉及product,category表
- 新闻模块：news,涉及news表

订单子系统：order,涉及order表

邮件子系统

## 约定
公共的domain、enumeration都放在common模块下
一般情况下api模块放service接口和exception异常
注意自己模块的异常放在自己模块的api模块下（Dubbo异常机制）
i18n资源文件放在common下即可，别的模块下不用放


## 启动顺序
email，order，user，web

## 注意事项
所有实体类都要实现serializable接口
Dubbo异常处理机制：
异常类和接口类在同一jar包里，直接抛出
，否则被调方service中抛出的异常，在调用方中会被包一层RuntimeException，无法获得原来的异常

## TCC 解决订单支付问题
### 概述
Try: 尝试执行业务

完成所有业务检查（一致性）

预留必须业务资源（准隔离性）
Confirm: 确认执行业务

真正执行业务

不作任何业务检查

只使用Try阶段预留的业务资源

Confirm操作满足幂等性
Cancel: 取消执行业务

释放Try阶段预留的业务资源

Cancel操作满足幂等性（因为可能会多次执行）

示例演示在下完订单后,使用红包帐户和资金帐户来付款，红包帐户服务和资金帐户服务在不同的系统中。
示例中，有两个SOA提供方，一个是CapitalTradeOrderService，代表着资金帐户服务,另一个是RedPacketTradeOrderService,代表着红包帐户服务。

下完订单后，订单状态为DRAFT，
在TCC事务中TRY阶段，
订单支付服务将订单状态变成PAYING，
同时远程调用红包帐户服务和资金帐户服务,将付款方的余额减掉（预留业务资源);
如果在TRY阶段，任何一个服务失败，tcc-transaction将自动调用这些服务对应的cancel方法，
订单支付服务将订单状态变成PAY_FAILED,
同时远程调用红包帐户服务和资金帐户服务,将付款方余额减掉的部分增加回去；
如果TRY阶段正常完成，则进入CONFIRM阶段，在CONFIRM阶段（tcc-transaction自动调用）,
订单支付服务将订单状态变成CONFIRMED,同时远程调用红包帐户服务和资金帐户服务对应的CONFIRM方法，
将收款方的余额增加。


特别说明下，由于是示例，在CONFIRM和CANCEL方法中没有实现幂等性，如果在真实项目中使用，需要保证CONFIRM和CANCEL方法的幂等性。

一个幂等的操作典型如：
把编号为5的记录的A字段设置为0
这种操作不管执行多少次都是幂等的。

一个非幂等的操作典型如：
把编号为5的记录的A字段增加1
这种操作显然就不是幂等的。


### 业务逻辑
try: 账户余额扣减，订单状态设置paying
confirm：订单状态设置为paid，收款方余额增加
cancel：账户余额回增，订单状态设置为pay_failed

扣减和增加需要实现幂等（Dubbo调用远程接口失败的话会重试）:
- cancel时如果订单状态不是paying，则不增加账户余额
- confirm时如果订单状态不是paying，则不增加收款方余额
- try时如果订单状态不是unpaid，则不扣减账户余额

**要求实现并发，因为是读取-判断-更新的执行序列。**

以try阶段为例：
1. 请求1读取订单状态
2. 请求2读取订单状态
3. 请求1判断订单状态为unpaid，将订单状态设置为paying，并进行账户余额扣减
4. 请求2判断订单状态为unpaid，执行同上操作

此时会扣减两次账户余额。
要求该执行序列要实现原子执行，比如MVCC方式。
在账户表中设置一个version。


### 流程

try：
1. 读取订单状态
2. 如果订单状态为unpaid，则将订单状态设置为paying，执行账户余额扣减(可能会抛出异常此时会执行cancel)


confirm: 
1. 读取订单状态
2. 如果订单是paying，则将订单状态设置为paid，执行收款方余额表的增加

cancel:
1. 读取订单状态
2. 如果订单状态是paying，则将订单状态设置为pay_failure，执行账户余额的回增

## 异步确保型（可靠消息最终一致）解决订单支付问题
由于远程事务的消息可能会重试，需要在业务上实现幂等（已经处理过的记录下来，遇到新的请求时 检查是否已经处理过）。






