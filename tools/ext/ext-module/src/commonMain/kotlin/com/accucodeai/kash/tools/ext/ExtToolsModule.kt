package com.accucodeai.kash.tools.ext

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.tools.base64.base64Commands
import com.accucodeai.kash.tools.bzip2.bzip2Commands
import com.accucodeai.kash.tools.clear.clearCommands
import com.accucodeai.kash.tools.column.columnCommands
import com.accucodeai.kash.tools.cpio.cpioCommands
import com.accucodeai.kash.tools.curl.curlCommands
import com.accucodeai.kash.tools.gzip.gzipCommands
import com.accucodeai.kash.tools.hexdump.hexdumpCommands
import com.accucodeai.kash.tools.lz4.lz4Commands
import com.accucodeai.kash.tools.pax.paxCommands
import com.accucodeai.kash.tools.printenv.printenvCommands
import com.accucodeai.kash.tools.reset.resetCommands
import com.accucodeai.kash.tools.rev.revCommands
import com.accucodeai.kash.tools.seq.seqCommands
import com.accucodeai.kash.tools.shasum.shaSumCommands
import com.accucodeai.kash.tools.tac.tacCommands
import com.accucodeai.kash.tools.tar.tarCommands
import com.accucodeai.kash.tools.timeout.timeoutCommands
import com.accucodeai.kash.tools.uuidgen.uuidgenCommands
import com.accucodeai.kash.tools.which.whichCommands
import com.accucodeai.kash.tools.whoami.whoamiCommands
import com.accucodeai.kash.tools.xz.xzCommands
import com.accucodeai.kash.tools.yes.yesCommands
import com.accucodeai.kash.tools.zip.zipCommands
import com.accucodeai.kash.tools.zstd.zstdCommands

/**
 * Non-POSIX-but-universal tools (GNU/BSD de-facto utilities) concatenated
 * into one list. Picked up by [com.accucodeai.kash.KashCoreModule].
 */
public val extCommands: List<CommandSpec> =
    base64Commands +
        bzip2Commands +
        clearCommands +
        columnCommands +
        cpioCommands +
        curlCommands +
        gzipCommands +
        hexdumpCommands +
        lz4Commands +
        paxCommands +
        printenvCommands +
        resetCommands +
        revCommands +
        seqCommands +
        shaSumCommands +
        tacCommands +
        tarCommands +
        timeoutCommands +
        uuidgenCommands +
        whichCommands +
        whoamiCommands +
        xzCommands +
        yesCommands +
        zipCommands +
        zstdCommands
