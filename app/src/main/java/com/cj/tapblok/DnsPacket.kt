package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import kotlin.math.min

object DnsPacket {
    data class ParsedQuery(
        val ipHeaderLength: Int,
        val totalLength: Int,
        val sourceAddress: Int,
        val destinationAddress: Int,
        val sourcePort: Int,
        val destinationPort: Int,
        val dnsPayload: ByteArray,
        val questionDomain: String
    )

    data class ParsedIpv6Query(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val dnsPayload: ByteArray,
        val questionDomain: String
    )

    fun parseUdpDnsQuery(packet: ByteArray, length: Int): ParsedQuery? {
        if (length < 28) return null
        val version = u8(packet, 0) shr 4
        val headerLength = (u8(packet, 0) and 0x0f) * 4
        if (version != 4 || headerLength < 20 || length < headerLength + 8) return null
        if (u8(packet, 9) != 17) return null

        val totalLength = min(u16(packet, 2), length)
        val fragment = u16(packet, 6)
        if ((fragment and 0x3fff) != 0) return null

        val udpOffset = headerLength
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpOffset + udpLength > totalLength) return null

        val destinationPort = u16(packet, udpOffset + 2)
        if (destinationPort != 53) return null

        val dnsPayloadOffset = udpOffset + 8
        val dnsPayloadLength = udpLength - 8
        val dnsPayload = packet.copyOfRange(dnsPayloadOffset, dnsPayloadOffset + dnsPayloadLength)
        val question = parseQuestion(dnsPayload) ?: return null

        return ParsedQuery(
            ipHeaderLength = headerLength,
            totalLength = totalLength,
            sourceAddress = i32(packet, 12),
            destinationAddress = i32(packet, 16),
            sourcePort = u16(packet, udpOffset),
            destinationPort = destinationPort,
            dnsPayload = dnsPayload,
            questionDomain = question.domain
        )
    }

    fun parseUdpDnsQueryV6(packet: ByteArray, length: Int): ParsedIpv6Query? {
        if (length < 48) return null
        val version = u8(packet, 0) shr 4
        if (version != 6 || u8(packet, 6) != 17) return null

        val payloadLength = u16(packet, 4)
        val totalLength = min(40 + payloadLength, length)
        val udpOffset = 40
        if (totalLength < udpOffset + 8) return null

        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpOffset + udpLength > totalLength) return null

        val destinationPort = u16(packet, udpOffset + 2)
        if (destinationPort != 53) return null

        val dnsPayloadOffset = udpOffset + 8
        val dnsPayloadLength = udpLength - 8
        val dnsPayload = packet.copyOfRange(dnsPayloadOffset, dnsPayloadOffset + dnsPayloadLength)
        val question = parseQuestion(dnsPayload) ?: return null

        return ParsedIpv6Query(
            sourceAddress = packet.copyOfRange(8, 24),
            destinationAddress = packet.copyOfRange(24, 40),
            sourcePort = u16(packet, udpOffset),
            destinationPort = destinationPort,
            dnsPayload = dnsPayload,
            questionDomain = question.domain
        )
    }

    fun buildBlockedResponsePacket(originalPacket: ByteArray, length: Int): ByteArray? {
        val query = parseUdpDnsQuery(originalPacket, length) ?: return null
        val payload = buildDnsResponsePayload(query.dnsPayload, responseCode = 3) ?: return null
        return wrapDnsPayload(originalPacket, query, payload)
    }

    fun buildBlockedResponsePacketV6(originalPacket: ByteArray, length: Int): ByteArray? {
        val query = parseUdpDnsQueryV6(originalPacket, length) ?: return null
        val payload = buildDnsResponsePayload(query.dnsPayload, responseCode = 3) ?: return null
        return wrapDnsPayloadV6(query, payload)
    }

    fun buildServerFailureResponsePacket(originalPacket: ByteArray, length: Int): ByteArray? {
        val query = parseUdpDnsQuery(originalPacket, length) ?: return null
        val payload = buildDnsResponsePayload(query.dnsPayload, responseCode = 2) ?: return null
        return wrapDnsPayload(originalPacket, query, payload)
    }

    fun buildServerFailureResponsePacketV6(originalPacket: ByteArray, length: Int): ByteArray? {
        val query = parseUdpDnsQueryV6(originalPacket, length) ?: return null
        val payload = buildDnsResponsePayload(query.dnsPayload, responseCode = 2) ?: return null
        return wrapDnsPayloadV6(query, payload)
    }

    fun buildAllowedResponsePacket(
        originalPacket: ByteArray,
        length: Int,
        dnsResponsePayload: ByteArray
    ): ByteArray? {
        val query = parseUdpDnsQuery(originalPacket, length) ?: return null
        return wrapDnsPayload(originalPacket, query, dnsResponsePayload)
    }

    fun buildAllowedResponsePacketV6(
        originalPacket: ByteArray,
        length: Int,
        dnsResponsePayload: ByteArray
    ): ByteArray? {
        val query = parseUdpDnsQueryV6(originalPacket, length) ?: return null
        return wrapDnsPayloadV6(query, dnsResponsePayload)
    }

    fun buildTcpDnsResetPacket(packet: ByteArray, length: Int): ByteArray? {
        val tcp = parseTcpDnsPacket(packet, length) ?: return null
        val totalLength = 20 + 20
        val response = ByteArray(totalLength)

        response[0] = 0x45
        response[1] = 0
        put16(response, 2, totalLength)
        put16(response, 4, u16(packet, 4))
        put16(response, 6, 0)
        response[8] = 64
        response[9] = 6
        put32(response, 12, tcp.destinationAddress)
        put32(response, 16, tcp.sourceAddress)
        put16(response, 10, checksum(response, 0, 20))

        val tcpOffset = 20
        put16(response, tcpOffset, tcp.destinationPort)
        put16(response, tcpOffset + 2, tcp.sourcePort)
        if (tcp.acknowledgementFlagSet) {
            put32(response, tcpOffset + 4, tcp.acknowledgementNumber)
            put32(response, tcpOffset + 8, 0)
            response[tcpOffset + 13] = 0x04
        } else {
            put32(response, tcpOffset + 4, 0)
            put32(response, tcpOffset + 8, tcp.sequenceNumber + tcp.acknowledgementIncrement)
            response[tcpOffset + 13] = 0x14
        }
        response[tcpOffset + 12] = 0x50
        put16(response, tcpOffset + 14, 0)
        put16(response, tcpOffset + 16, 0)
        put16(response, tcpOffset + 18, 0)
        put16(response, tcpOffset + 16, tcpChecksum(response, tcpOffset, 20, tcp.destinationAddress, tcp.sourceAddress))

        return response
    }

    private data class DnsQuestion(val domain: String, val questionEnd: Int)

    private data class TcpDnsPacket(
        val sourceAddress: Int,
        val destinationAddress: Int,
        val sourcePort: Int,
        val destinationPort: Int,
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val acknowledgementIncrement: Long,
        val acknowledgementFlagSet: Boolean
    )

    private fun parseTcpDnsPacket(packet: ByteArray, length: Int): TcpDnsPacket? {
        if (length < 40) return null
        val version = u8(packet, 0) shr 4
        val headerLength = (u8(packet, 0) and 0x0f) * 4
        if (version != 4 || headerLength < 20 || length < headerLength + 20) return null
        if (u8(packet, 9) != 6) return null

        val totalLength = min(u16(packet, 2), length)
        val fragment = u16(packet, 6)
        if ((fragment and 0x3fff) != 0) return null

        val tcpOffset = headerLength
        val tcpHeaderLength = (u8(packet, tcpOffset + 12) shr 4) * 4
        if (tcpHeaderLength < 20 || tcpOffset + tcpHeaderLength > totalLength) return null

        val destinationPort = u16(packet, tcpOffset + 2)
        if (destinationPort != 53) return null

        val flags = u8(packet, tcpOffset + 13)
        val payloadLength = totalLength - tcpOffset - tcpHeaderLength
        val acknowledgementIncrement = payloadLength +
            (if ((flags and 0x02) != 0) 1 else 0) +
            (if ((flags and 0x01) != 0) 1 else 0)

        return TcpDnsPacket(
            sourceAddress = i32(packet, 12),
            destinationAddress = i32(packet, 16),
            sourcePort = u16(packet, tcpOffset),
            destinationPort = destinationPort,
            sequenceNumber = u32(packet, tcpOffset + 4),
            acknowledgementNumber = u32(packet, tcpOffset + 8),
            acknowledgementIncrement = acknowledgementIncrement.toLong(),
            acknowledgementFlagSet = (flags and 0x10) != 0
        )
    }

    private fun parseQuestion(payload: ByteArray): DnsQuestion? {
        if (payload.size < 12) return null
        val questionCount = u16(payload, 4)
        if (questionCount < 1) return null

        val labels = mutableListOf<String>()
        var cursor = 12
        while (cursor < payload.size) {
            val labelLength = u8(payload, cursor)
            cursor += 1
            if (labelLength == 0) break
            if ((labelLength and 0xc0) != 0 || labelLength > 63) return null
            if (cursor + labelLength > payload.size) return null
            labels += payload.copyOfRange(cursor, cursor + labelLength).decodeToString()
            cursor += labelLength
        }

        if (labels.isEmpty() || cursor + 4 > payload.size) return null
        return DnsQuestion(labels.joinToString(".").lowercase(), cursor + 4)
    }

    private fun buildDnsResponsePayload(queryPayload: ByteArray, responseCode: Int): ByteArray? {
        val question = parseQuestion(queryPayload) ?: return null
        val response = queryPayload.copyOfRange(0, question.questionEnd)
        response[2] = 0x81.toByte()
        response[3] = (0x80 or responseCode).toByte()
        response[4] = 0
        response[5] = 1
        response[6] = 0
        response[7] = 0
        response[8] = 0
        response[9] = 0
        response[10] = 0
        response[11] = 0
        return response
    }

    private fun wrapDnsPayload(
        originalPacket: ByteArray,
        query: ParsedQuery,
        dnsPayload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + dnsPayload.size
        val response = ByteArray(totalLength)

        response[0] = 0x45
        response[1] = 0
        put16(response, 2, totalLength)
        put16(response, 4, u16(originalPacket, 4))
        put16(response, 6, 0)
        response[8] = 64
        response[9] = 17
        put32(response, 12, query.destinationAddress)
        put32(response, 16, query.sourceAddress)
        put16(response, 10, checksum(response, 0, 20))

        val udpOffset = 20
        put16(response, udpOffset, query.destinationPort)
        put16(response, udpOffset + 2, query.sourcePort)
        put16(response, udpOffset + 4, 8 + dnsPayload.size)
        put16(response, udpOffset + 6, 0)
        dnsPayload.copyInto(response, udpOffset + 8)

        return response
    }

    private fun wrapDnsPayloadV6(query: ParsedIpv6Query, dnsPayload: ByteArray): ByteArray {
        val udpLength = 8 + dnsPayload.size
        val totalLength = 40 + udpLength
        val response = ByteArray(totalLength)

        response[0] = 0x60
        put16(response, 4, udpLength)
        response[6] = 17
        response[7] = 64
        query.destinationAddress.copyInto(response, 8)
        query.sourceAddress.copyInto(response, 24)

        val udpOffset = 40
        put16(response, udpOffset, query.destinationPort)
        put16(response, udpOffset + 2, query.sourcePort)
        put16(response, udpOffset + 4, udpLength)
        put16(response, udpOffset + 6, 0)
        dnsPayload.copyInto(response, udpOffset + 8)
        val checksum = udpChecksumIpv6(response, udpOffset, udpLength, query.destinationAddress, query.sourceAddress)
        put16(response, udpOffset + 6, if (checksum == 0) 0xffff else checksum)

        return response
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var cursor = offset
        while (cursor < offset + length) {
            val high = u8(buffer, cursor)
            val low = if (cursor + 1 < offset + length) u8(buffer, cursor + 1) else 0
            sum += (high shl 8) + low
            while (sum > 0xffff) {
                sum = (sum and 0xffff) + (sum ushr 16)
            }
            cursor += 2
        }
        return sum.inv() and 0xffff
    }

    private fun tcpChecksum(
        buffer: ByteArray,
        tcpOffset: Int,
        tcpLength: Int,
        sourceAddress: Int,
        destinationAddress: Int
    ): Int {
        var sum = 0
        sum = add16(sum, (sourceAddress ushr 16) and 0xffff)
        sum = add16(sum, sourceAddress and 0xffff)
        sum = add16(sum, (destinationAddress ushr 16) and 0xffff)
        sum = add16(sum, destinationAddress and 0xffff)
        sum = add16(sum, 6)
        sum = add16(sum, tcpLength)

        var cursor = tcpOffset
        while (cursor < tcpOffset + tcpLength) {
            val high = u8(buffer, cursor)
            val low = if (cursor + 1 < tcpOffset + tcpLength) u8(buffer, cursor + 1) else 0
            sum = add16(sum, (high shl 8) + low)
            cursor += 2
        }
        return sum.inv() and 0xffff
    }

    private fun udpChecksumIpv6(
        buffer: ByteArray,
        udpOffset: Int,
        udpLength: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray
    ): Int {
        var sum = 0
        for (index in sourceAddress.indices step 2) {
            sum = add16(sum, (u8(sourceAddress, index) shl 8) + u8(sourceAddress, index + 1))
        }
        for (index in destinationAddress.indices step 2) {
            sum = add16(sum, (u8(destinationAddress, index) shl 8) + u8(destinationAddress, index + 1))
        }
        sum = add16(sum, (udpLength ushr 16) and 0xffff)
        sum = add16(sum, udpLength and 0xffff)
        sum = add16(sum, 17)

        var cursor = udpOffset
        while (cursor < udpOffset + udpLength) {
            val high = u8(buffer, cursor)
            val low = if (cursor + 1 < udpOffset + udpLength) u8(buffer, cursor + 1) else 0
            sum = add16(sum, (high shl 8) + low)
            cursor += 2
        }
        return sum.inv() and 0xffff
    }

    private fun add16(current: Int, value: Int): Int {
        var sum = current + value
        while (sum > 0xffff) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }
        return sum
    }

    private fun u8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt() and 0xff

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (u8(buffer, offset) shl 8) or u8(buffer, offset + 1)

    private fun i32(buffer: ByteArray, offset: Int): Int =
        (u8(buffer, offset) shl 24) or
            (u8(buffer, offset + 1) shl 16) or
            (u8(buffer, offset + 2) shl 8) or
            u8(buffer, offset + 3)

    private fun u32(buffer: ByteArray, offset: Int): Long =
        ((u8(buffer, offset).toLong() shl 24) or
            (u8(buffer, offset + 1).toLong() shl 16) or
            (u8(buffer, offset + 2).toLong() shl 8) or
            u8(buffer, offset + 3).toLong()) and 0xffffffffL

    private fun put16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun put32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    private fun put32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}
