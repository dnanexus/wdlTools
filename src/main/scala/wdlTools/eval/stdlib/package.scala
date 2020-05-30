package wdlTools.eval

import java.net.URL

import wdlTools.syntax.WdlVersion
import wdlTools.util.Options

package object stdlib {
  def getStandardLibraryImpl(wdlVersion: WdlVersion,
                             opts: Options,
                             evalCfg: EvalConfig,
                             docSourceUrl: Option[URL]): StandardLibraryImpl = {
    wdlVersion match {
      case WdlVersion.Draft_2 => StdlibDraft2(opts, evalCfg, docSourceUrl)
      case WdlVersion.V1      => StdlibV1(opts, evalCfg, docSourceUrl)
      case WdlVersion.V2      => StdlibV2(opts, evalCfg, docSourceUrl)
      case other              => throw new RuntimeException(s"Version ${other} is not supported")
    }
  }
}
