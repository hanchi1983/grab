package com.javachen.grab

import java.util

import org.apache.log4j.{Level, Logger}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable
import scala.sys.process._

/**
 * 基于物品的协同过滤，用户和商品做笛卡尔连接，占用太多内存，程序无法运行
 */
object UserGoodsCF2{

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("UserGoodsCF")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.registerKryoClasses(Array(classOf[mutable.BitSet],classOf[(Int, Int)],classOf[Rating]))
    conf.set("spark.kryoserializer.buffer.mb", "16")
    //conf.set("spark.kryo.registrator", "MyRegistrator")
    conf.set("spark.akka.frameSize", "500");

    val sc = new SparkContext(conf)
    val hc = new HiveContext(sc)
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.apache.hadoop").setLevel(Level.OFF)
    Logger.getLogger("org.eclipse.jetty.server").setLevel(Level.OFF)

    //表结构：user_id,goods_id,visit_time,city_id,visit_count,buy_count,is_collect,avg_score
    val ratings = hc.sql("select * from dw_rec.user_goods_preference_filter").map { t =>
      var score = 0.0
      if (t(7).asInstanceOf[Double] > 0) {
        score = t(7).asInstanceOf[Double] //评论
      } else {
        if (t(4).asInstanceOf[Int] > 0) score += 5 * 0.3 //访问
        if (t(5).asInstanceOf[Int] > 0) score += 5 * 0.6 //购买
        if (t(6).asInstanceOf[Byte] > 0) score += 5 * 0.1 //搜藏
      }
      //((user,city),Rating)
      ((t(0).asInstanceOf[Int], t(3).asInstanceOf[Int]), Rating(t(0).asInstanceOf[Int], t(1).asInstanceOf[Int], score))
    }.repartition(128)

    val userCitys = ratings.keys.groupByKey().map { case (user, list) => (user, list.last) } //对每个用户取最近的一个城市
    val training = ratings.values.setName("training").persist(StorageLevel.MEMORY_AND_DISK_SER)

    val trainingUsers = training.map(_.user).distinct(4)
    val trainingGoods = training.map(_.product).distinct(4)
    var start = System.currentTimeMillis()
    println("Got " + training.count() + " ratings from " + trainingUsers.count + " users on " + trainingGoods.count + " goods. ")
    println("Count Time = " + (System.currentTimeMillis() - start) * 1.0 / 1000)

    //1.训练模型
    val rank = 12
    val numIterations = 20
    val lambda = 0.01
    start = System.currentTimeMillis()
    val model = ALS.trainImplicit(training, rank, numIterations, lambda, 1.0)
    println("Train Time = " + (System.currentTimeMillis() - start) * 1.0 / 1000)

    // 2.给所有用户推荐在线的商品
    val onlineGoodsSpRDD = hc.sql("select goods_id,sp_id from dw_rec.online_goods").map { t =>
      (t(0).asInstanceOf[Int], t(1).asInstanceOf[Int])
    }
    val onlineGoodsSp= onlineGoodsSpRDD.collectAsMap() //TODO 广播变量
    val onlineGoods= onlineGoodsSpRDD.map(_._1)
    val hotGoodsRDD = hc.sql("select city_id,goods_id,total_amount from dw_rec.hot_goods").map { t =>
      (t(0).asInstanceOf[Int], (t(1).asInstanceOf[Int], t(2).asInstanceOf[Int]))
    }.groupByKey().map { case (city_id, list) =>
      (city_id, list.toArray.sortBy { case (goods_id, total_num) => -total_num }.take(30).map { case (goods_id, total_num) => goods_id })
    }
    val sendGoods = hotGoodsRDD.lookup(9999).head //配送商品，city=9999 TODO 广播变量

    val candidates = userCitys.map(_._1).cartesian(onlineGoods)
    var recommendsTopN = model.predict(candidates).map { case Rating(user, product, rate) =>
      (user, (product, rate))
    }.groupByKey().map{case (user,list)=>
      (user,list.toArray.sortBy {case (product,rate)=> - rate}.take(40).map{case (product,rate)=>product})
    }

    //3.对推荐结果补齐数据、过滤、去重
    val recommendsTopNWithCity=recommendsTopN.join(userCitys)
    val lastUserRecommends=recommendsTopNWithCity.map{case (user,(list1,city_id))=>
      (city_id,(user,list1))
    }.join(hotGoodsRDD).map{case (city_id,((user,list1),list2))=>
      filterGoods(list1,list2,sendGoods,onlineGoodsSp)
    }

    //对其他没有被推荐的用户构造推荐列表：热销商品+配送商品
    val otherUsersRecommends=hotGoodsRDD.map{ case (city_id, list2) =>
      filterGoods(Array(),list2,sendGoods,onlineGoodsSp)
    }

    //保存结果
    start = System.currentTimeMillis()
    "hadoop fs -rm -r /tmp/user_goods_rec".!
    lastUserRecommends.union(otherUsersRecommends).saveAsTextFile("/tmp/user_goods_rec")
    print("SaveAsTextFile Time = " + (System.currentTimeMillis() - start) * 1.0 / 1000)

    //保存结果到redis
    start = System.currentTimeMillis()
    lastUserRecommends.union(otherUsersRecommends).foreachPartition(partitionOfRecords => {
      var values=new util.HashMap[String,String]()
      partitionOfRecords.foreach(pair => {
//        val jedis = RedisClient.pool.getResource
//        values = new util.HashMap[String,String]()
//        values.put("city",pair._2.toString)
//        values.put("rec",pair._3.mkString(","))
//        jedis.hmset(pair._1.toString,values)
//        jedis.close()
      })
    })
    print("SaveToRedis Time = " + (System.currentTimeMillis() - start) * 1.0 / 1000)
    ratings.unpersist()

    sc.stop()

    print("All Done")
  }

  /**
   * 过滤商品，取在线商品并且一个商家只推一个商品
   */
  def filterGoods(recommends: Array[Int], hotGoods: Array[Int], sendGoods: Array[Int], onlineGoodsSp: scala.collection.Map[Int, Int]): String = {
    val toFilterGoods = recommends ++ hotGoods ++ sendGoods
    var filtered = scala.collection.mutable.Map[Int, Int]()
    var sp_id = -1

    for (product <- toFilterGoods if (filtered.size < 40)) {
      sp_id = onlineGoodsSp.get(product).getOrElse(-1)
      if (sp_id > 0 && !filtered.contains(sp_id)) {
        //sp_id -> goods_id
        filtered += sp_id -> product
      }
    }
    filtered.values.mkString(",")
  }
  /** Compute RMSE (Root Mean Squared Error). */
  def computeRmse(model: MatrixFactorizationModel, data: RDD[Rating]) = {
    val usersProducts = data.map { case Rating(user, product, rate) =>
      (user, product)
    }

    val predictions = model.predict(usersProducts).map { case Rating(user, product, rate) =>
      ((user, product), rate)
    }

    val ratesAndPreds = data.map { case Rating(user, product, rate) =>
      ((user, product), rate)
    }.join(predictions)

    math.sqrt(ratesAndPreds.map { case ((user, product), (r1, r2)) =>
      val err = (r1 - r2)
      err * err
    }.mean())
  }
}
