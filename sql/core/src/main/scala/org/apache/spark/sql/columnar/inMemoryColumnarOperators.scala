/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql
package columnar

import org.apache.spark.sql.catalyst.expressions.{GenericMutableRow, Attribute}
import org.apache.spark.sql.execution.{SparkPlan, LeafNode}

private[sql] case class InMemoryColumnarTableScan(attributes: Seq[Attribute], child: SparkPlan)
  extends LeafNode {

  // For implicit conversion from `DataType` to `ColumnType`
  import ColumnType._

  override def output: Seq[Attribute] = attributes

  lazy val cachedColumnBuffers = {
    val output = child.output
    val cached = child.execute().mapPartitions { iterator =>
      val columnBuilders = output.map { a =>
        ColumnBuilder(a.dataType.typeId, 0, a.name)
      }.toArray

      var row: Row = null
      while (iterator.hasNext) {
        row = iterator.next()
        var i = 0
        while (i < row.length) {
          columnBuilders(i).appendFrom(row, i)
          i += 1
        }
      }

      Iterator.single(columnBuilders.map(_.build()))
    }.cache()

    cached.setName(child.toString)
    // Force the materialization of the cached RDD.
    cached.count()
    cached
  }

  override def execute() = {
    cachedColumnBuffers.mapPartitions { iterator =>
      val columnBuffers = iterator.next()
      assert(!iterator.hasNext)

      new Iterator[Row] {
        val columnAccessors = columnBuffers.map(ColumnAccessor(_))
        val nextRow = new GenericMutableRow(columnAccessors.length)

        override def next() = {
          var i = 0
          while (i < nextRow.length) {
            columnAccessors(i).extractTo(nextRow, i)
            i += 1
          }
          nextRow
        }

        override def hasNext = columnAccessors.head.hasNext
      }
    }
  }
}
