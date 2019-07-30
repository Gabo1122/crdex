package com.wavesplatform.dex.cache

import java.util.concurrent.ConcurrentHashMap

import com.wavesplatform.dex.model.BriefAssetDescription
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.assets.exchange.AssetPair
import com.wavesplatform.utils.ScorexLogging

class AssetDecimalsCache(assetDescription: IssuedAsset => Option[BriefAssetDescription]) extends ScorexLogging {

  private val WavesDecimals      = 8
  private val assetDecimalsCache = new ConcurrentHashMap[Asset, Int](1000, 0.9f, 10)

  def get(asset: Asset): Int = {
    asset.fold { WavesDecimals } { issuedAsset =>
      Option(assetDecimalsCache.get(asset)) getOrElse {
        val assetDecimals =
          assetDescription(issuedAsset)
            .map(_.decimals)
            .getOrElse {
              log.error(s"Can not get asset decimals since asset '${AssetPair.assetIdStr(asset)}' not found!")
              8
            }

        assetDecimalsCache.put(issuedAsset, assetDecimals)
        assetDecimals
      }
    }
  }
}