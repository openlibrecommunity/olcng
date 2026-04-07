package xyz.zarazaex.olc.fmt

import xyz.zarazaex.olc.dto.ProfileItem
import xyz.zarazaex.olc.dto.V2rayConfig.OutboundBean
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.extension.isNotNullEmpty
import xyz.zarazaex.olc.handler.V2rayConfigManager

object HttpFmt : FmtBase() {
    /**
     * Converts a ProfileItem object to an OutboundBean object.
     *
     * @param profileItem the ProfileItem object to convert
     * @return the converted OutboundBean object, or null if conversion fails
     */
    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = V2rayConfigManager.createInitOutbound(EConfigType.HTTP)

        outboundBean?.settings?.servers?.first()?.let { server ->
            server.address = getServerAddress(profileItem)
            server.port = profileItem.serverPort.orEmpty().toInt()
            if (profileItem.username.isNotNullEmpty()) {
                val socksUsersBean = OutboundBean.OutSettingsBean.ServersBean.SocksUsersBean()
                socksUsersBean.user = profileItem.username.orEmpty()
                socksUsersBean.pass = profileItem.password.orEmpty()
                server.users = listOf(socksUsersBean)
            }
        }

        return outboundBean
    }
}