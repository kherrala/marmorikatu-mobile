package fi.marmorikatu.core.fixtures

/**
 * Retained MQTT payloads captured verbatim from the live broker
 * (freenas.kherrala.fi) on 2026-07-09. Regenerate with the capture
 * script if the PLC publisher schema changes.
 */
object MqttFixtures {
    // marmorikatu/lights
    const val LIGHTS: String = """{"1":false,"2":false,"3":false,"4":false,"5":false,"6":false,"7":false,"8":false,"17":false,"18":false,"19":false,"20":false,"21":false,"22":false,"23":false,"24":false,"25":false,"26":false,"27":false,"28":false,"29":false,"30":false,"31":false,"32":false,"33":false,"34":false,"35":false,"36":false,"37":false,"38":false,"39":false,"40":false,"41":false,"42":false,"43":true,"44":false,"45":false,"46":false,"47":false,"48":false,"49":false,"50":false,"51":true,"52":false,"53":false,"54":false,"55":false,"56":false,"59":false,"60":false,"61":false}"""

    // marmorikatu/names/lights
    const val LIGHT_NAMES: String = """{"1":"Kylpyhuone","2":"Keittiö kaapisto ylä","3":"Yläkerta aula ledi","4":"Saunan laude ledi","5":"Olohuone ledi","6":"Kodinhoitohuone ledi","7":"Keittiö kaapisto ala","8":"Keittiö katto","17":"MH alakerta kattovalo","18":"MH alakerta ikkuna","19":"Ruokailu","20":"Ruokailu ikkuna","21":"","22":"MH2 Kattovalo","23":"MH2 ikkunavalo","24":"MH2 ikkunavalo","25":"Aula rappuset","26":"Yläkerta aula kattovalo","27":"","28":"MH3 Kattovalo","29":"Kylpyhuone yläkerta katto","30":"MH3 ikkunavalo","31":"MH1 Vaatehuone","32":"MH1 ikkunavalo","33":"MH1 Kattovalo","34":"Kylpuhuone yk peilivalo","35":"Eteinen","36":"Tuulikaappi vaatehuone","37":"Tuulikaappi","38":"Sauna siivousvalo","39":"Tekninen tila","40":"Keittiö Kattovalo","41":"Keittiö ikkunavalo","42":"Portaikko","43":"Kodinhoitohuone vaatehuone","44":"WC Alakerta katto","45":"WC Alakerta peili","46":"Olohuone ikkuna","47":"Sisäänkäynti","48":"Ulkovalo Terassi","49":"Kellari etuosa","50":"Kellari takaosa","51":"Kellari biljardipöytä","52":"Kellari WC","53":"Kellari varasto","54":"Olohuone kattovalo","55":"Kodinhoitohuone kattovalo","56":"Kodinhoitohuone kattovalo 2","59":"Autokatos","60":"Varasto ulkovalo","61":"Varasto"}"""

    // marmorikatu/outlets
    const val OUTLETS: String = """{"ulkopistorasia":false,"autokatos_pistorasia":false}"""

    // marmorikatu/temperatures
    const val TEMPERATURES: String = """{"yk_aula":22.6,"yk_aatu":22.4,"yk_onni":22.8,"yk_essi":23.3,"keittio":23.7,"mh_ak":21.9,"eteinen":21.8,"kellari_eteinen":20.8,"kellari":21.4,"tuloilmakanava":21.2,"jaahdpatteri_1":21.8,"jaahdpatteri_2":9.0}"""

    // marmorikatu/heating
    const val HEATING: String = """{"essi":0,"aatu":0,"onni":0,"yk_aula":0,"keittio":0,"mh_ak":1,"eteinen":0,"kellari_eteinen":0,"kellari":0}"""

    // marmorikatu/cooling
    const val COOLING: String = """{"pumppu_jaahdytys":true,"jaahdytyspumppu":false}"""

    // marmorikatu/ventilation
    const val VENTILATION: String = """{"OperatingMode":1,"HeaterCooling":0,"OutdoorTemp":18.2,"SupplyTempPreHeat":22.4,"ExtractTemp":24.5,"SupplyTempPostHeat":24.1,"ExhaustTemp":20.5,"RelativeHumidity":43.8,"AbsHumidity":9.86,"Enthalpy":46.2,"DewPoint":11.5,"Belimo22DTH_Temp":24.6,"DamperPosition":100.0,"AfterheaterOvertemp":0.0,"PreheaterOvertemp":0.0,"RoomTemp":0.0,"HreEfficiency":0,"IndoorRH":0,"SupplyFanSpeed":0,"ExhaustFanSpeed":0,"HxBypassOpen":false,"AlarmIRSensor":false,"AlarmTempDeviation":false,"AlarmFreezingDanger":false,"AlarmFilterGuard":false,"AlarmOverheatAfter":false,"AlarmEfficiency":false,"AlarmFanFailureSA":false,"AlarmFanFailureEA":false,"AlarmServiceReminder":false,"AlarmTempSensor":0}"""

    // marmorikatu/status
    const val STATUS: String = """{"PublishCount":1612631,"ErrorCount":0,"ModbusConnected":true,"ModbusConsecutiveErrors":0,"HeatPumpFails":0,"ExtraHeaterFails":0,"CommandsReceived":2056,"CommandsApplied":2056,"CommandsRejected":0,"CommandTopicMisses":0}"""

    // marmorikatu/energy/heatpump
    const val ENERGY_HEATPUMP: String = """{"L1_Voltage":232.5,"L2_Voltage":231.7,"L3_Voltage":230.4,"Grid_Frequency":50.0,"L1_Current":0.0,"L2_Current":0.0,"L3_Current":0.0,"Total_Active_Power":0.0,"L1_Active_Power":0.0,"L2_Active_Power":0.0,"L3_Active_Power":0.0,"Total_Active_Energy":40624.77,"L1_Total_Active_Energy":14694.06,"L2_Total_Active_Energy":13133.3,"L3_Total_Active_Energy":12797.41,"Forward_Active_Energy":40622.81,"Reverse_Active_Energy":1.96}"""

    // marmorikatu/switches
    const val SWITCHES: String = """{"in1":false,"in2":false,"in3":false,"in4":false,"in5":false,"in6":false,"in7":false,"in8":false,"in9":false,"in10":false,"in11":false,"in12":false,"in13":false,"in14":false,"in15":false,"in16":false,"in17":false,"in18":false,"in19":false,"in20":false,"in21":false,"in22":false,"in23":false,"in24":false,"in25":false,"in26":false,"in27":false,"in28":false,"in29":false,"in30":false,"in31":false,"in32":false,"in33":false,"in34":false,"in35":false,"in36":false,"in37":false,"in38":false,"in39":false,"in40":false,"in41":false,"in42":false,"in43":false,"in44":false,"in45":false,"in46":false,"in47":false,"in48":false,"in49":false,"in50":false,"in51":false,"in52":false,"in53":false,"in54":false,"in55":false,"in56":false}"""

}
