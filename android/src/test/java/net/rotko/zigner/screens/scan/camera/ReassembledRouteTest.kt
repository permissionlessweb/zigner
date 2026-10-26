package net.rotko.zigner.screens.scan.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ReassembledRouteTest {
	@Test fun cosmosPrefixRoutesToCosmos() {
		assertEquals(ReassembledRoute.COSMOS, reassembledRoute("530510000000000474657270"))
		assertEquals(ReassembledRoute.COSMOS, reassembledRoute("530510"))
	}

	@Test fun otherPrefixes() {
		assertEquals(ReassembledRoute.PENUMBRA, reassembledRoute("530310ab"))
		assertEquals(ReassembledRoute.ZCASH_PCZT, reassembledRoute("530403ab"))
		assertEquals(ReassembledRoute.SUBSTRATE, reassembledRoute("530102ab"))
		assertEquals(ReassembledRoute.SUBSTRATE, reassembledRoute("5305"))
		assertEquals(ReassembledRoute.SUBSTRATE, reassembledRoute(""))
	}

	@Test fun rawRaptorFrameIsNotCosmos() {
		// The first raw raptor frame of the fixture starts with its header,
		// not 530510; only the reassembled payload is classified.
		assertEquals(ReassembledRoute.SUBSTRATE, reassembledRoute("80003f40000000005305"))
	}
}
