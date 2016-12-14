package com.wavesplatform.matcher.model

import com.wavesplatform.matcher.model.Events.{OrderCanceled, OrderExecuted}
import com.wavesplatform.matcher.model.LimitOrder.OrderStatus
import com.wavesplatform.matcher.util.{Cache, TTLCache}
import scorex.transaction.AssetAcc
import scorex.transaction.assets.exchange.Order
import scorex.transaction.state.database.state._

import scala.collection.mutable
import scala.concurrent.duration._

trait OrderHistory {
  val assetsToSpend = mutable.Map.empty[Address, Long]
  val ordersRemainingAmount: Cache[String, (Long, Long)] =
    TTLCache[String, (Long, Long)]((Order.MaxLiveTime + 3600*1000).millis)


  def initOrdersCache(m: Map[String, (Long, Long)]) = {
    ordersRemainingAmount.clear()
    m.foreach(v => ordersRemainingAmount.set(v._1, v._2))
  }

  def recoverFromOrderBook(ob: OrderBook): Unit = {
    ob.bids.foreach(_._2.foreach(addAssetsToSpend))
    ob.asks.foreach(_._2.foreach(addAssetsToSpend))
  }

  private def addAssetsToSpend(lo: LimitOrder) = {
    val order = lo.order
    val assetAcc = AssetAcc(order.sender, order.spendAssetId)
    val feeAssetAcc = AssetAcc(order.sender, None)
    assetsToSpend(assetAcc.key) = assetsToSpend.getOrElse(assetAcc.key, 0L) + lo.getSpendAmount
    assetsToSpend(feeAssetAcc.key) = assetsToSpend.getOrElse(feeAssetAcc.key, 0L) + lo.feeAmount
  }

  def addOpenOrder(lo: LimitOrder): Unit = {
    addAssetsToSpend(lo)

    ordersRemainingAmount.set(lo.order.idStr, (lo.order.amount, 0L))
  }

  def reduceSpendAssets(limitOrder: LimitOrder) = {
    def reduce(key: Address, value: Long) =
      if (assetsToSpend.contains(key)) {
        val newVal = assetsToSpend(key) - value
        if (newVal > 0) assetsToSpend += (key -> newVal)
        else assetsToSpend -= key
      }

    val order = limitOrder.order
    val assetAcc = AssetAcc(order.sender, order.spendAssetId)
    val feeAssetAcc = AssetAcc(order.sender, None)

    reduce(assetAcc.key, limitOrder.getSpendAmount)
    reduce(feeAssetAcc.key, limitOrder.feeAmount)
  }


  def didOrderExecuted(orderExecuted: OrderExecuted): Unit = {

    def updateRemaining(limitOrder: LimitOrder) = {
      val prev = ordersRemainingAmount.get(limitOrder.order.idStr).map(_._2).getOrElse(0L)
      ordersRemainingAmount.set(limitOrder.order.idStr, (limitOrder.order.amount, prev + limitOrder.amount))
    }

    reduceSpendAssets(orderExecuted.counterExecuted)

    updateRemaining(orderExecuted.submittedExecuted)
    updateRemaining(orderExecuted.counterExecuted)
  }

  def didOrderCanceled(orderCanceled: OrderCanceled): Unit = {
    val prev = ordersRemainingAmount.get(orderCanceled.limitOrder.order.idStr).map(_._2).getOrElse(0L)
    ordersRemainingAmount.set(orderCanceled.limitOrder.order.idStr, (0L, prev))

    reduceSpendAssets(orderCanceled.limitOrder)
  }

  def getOrderStatus(id: String): OrderStatus = {
    if (!ordersRemainingAmount.contains(id))
      LimitOrder.NotFound
    else {
      val (full, filled) = ordersRemainingAmount.get(id).get
      if (full == 0) LimitOrder.Cancelled(filled)
      else if (filled == 0) LimitOrder.Accepted
      else if (filled < full) LimitOrder.PartiallyFilled(filled)
      else LimitOrder.Filled
    }
  }
}
