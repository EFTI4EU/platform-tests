<?xml version="1.0" encoding="UTF-8"?>
<!--
  Context-free rules for the messages a platform sends (eDelivery schemas v0.5).
  Each assertion carries the ID of the test case it belongs to in @role, so that a failed assertion maps to a
  finding of that test case. Rules that need other messages or the test context are implemented in code.
-->
<sch:schema xmlns:sch="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt3">
  <sch:title>eFTI platform messages (ri-v0.5)</sch:title>

  <sch:ns prefix="ed" uri="http://efti.eu/v1/edelivery"/>
  <sch:ns prefix="id" uri="http://efti.eu/v1/consignment/identifier"/>
  <sch:ns prefix="common" uri="http://efti.eu/v1/consignment/common"/>
  <sch:ns prefix="xs" uri="http://www.w3.org/2001/XMLSchema"/>

  <!-- Status codes meaning success; the validator passes status.success of format.properties -->
  <sch:let name="successStatus" value="('200')"/>
  <!-- RFC 9562 UUID version 4 (version nibble 4, variant 10xx); hex digits are case-insensitive on input -->
  <sch:let name="uuidV4" value="'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'"/>

  <sch:pattern id="dataset-id">
    <sch:rule context="/ed:saveIdentifiersRequest">
      <sch:assert role="T-DAT-01" test="matches(@datasetId, $uuidV4)">
        The data set ID '<sch:value-of select="@datasetId"/>' is not a UUID version 4 (RFC 9562).
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <!-- The XSD only limits the characters and length of a date. A value not matching its format code stays unchanged
       by replace() and therefore cannot be cast either. -->
  <sch:pattern id="date-values">
    <sch:rule context="*[@formatId]">
      <sch:let name="value" value="normalize-space(.)"/>
      <sch:let name="isoDateTime" value="
        if (@formatId = '102') then replace($value, '^(\d{4})(\d{2})(\d{2})$', '$1-$2-$3T00:00:00')
        else if (@formatId = '203') then replace($value, '^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})$', '$1-$2-$3T$4:$5:00')
        else replace($value, '^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})([+\-]\d{2})(\d{2})$', '$1-$2-$3T$4:$5:00$6:$7')"/>
      <sch:assert role="T-DAT-01" test="$isoDateTime castable as xs:dateTime">
        The value '<sch:value-of select="."/>' of <sch:value-of select="local-name()"/> is not a real date and time in
        format code <sch:value-of select="@formatId"/> (102 = CCYYMMDD, 203 = CCYYMMDDHHMM, 205 = CCYYMMDDHHMMZHHMM).
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <!-- Del. Reg. (EU) 2024/2024 allows no format 203 on these identifiers, and the reference gate cannot read it there -->
  <sch:pattern id="identifier-date-formats">
    <sch:rule context="*:carrierAcceptanceDateTime | *:deliveryEvent/*:actualOccurrenceDateTime">
      <sch:assert role="T-DAT-01" test="not(@formatId = '203')">
        Format code 203 is not allowed on <sch:value-of select="local-name()"/>.
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <sch:pattern id="status-logic">
    <sch:rule context="/ed:uilResponse[@status = $successStatus]">
      <sch:assert role="T-DAT-02" test="exists(common:consignment)">
        A data response with success status '<sch:value-of select="@status"/>' does not carry the data set.
      </sch:assert>
      <sch:assert role="T-DAT-02" test="empty(ed:description)">
        A data response with success status '<sch:value-of select="@status"/>' carries an error description.
      </sch:assert>
    </sch:rule>
    <sch:rule context="/ed:uilResponse">
      <sch:assert role="T-DAT-02" test="normalize-space(string-join(ed:description)) != ''">
        A data response with status '<sch:value-of select="@status"/>' does not carry an error description.
      </sch:assert>
      <sch:assert role="T-DAT-02" test="empty(common:consignment)">
        A data response with status '<sch:value-of select="@status"/>' carries a data set.
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <sch:pattern id="identifier-set">
    <sch:rule context="/ed:saveIdentifiersRequest/id:consignment">
      <sch:assert role="T-DAT-03" test="exists(id:mainCarriageTransportMovement/id:modeCode)">
        The identifier upload does not contain the mode of transport.
      </sch:assert>
      <sch:assert role="T-DAT-03" test="exists(id:mainCarriageTransportMovement/id:dangerousGoodsIndicator)">
        The identifier upload does not contain the dangerous goods indicator.
      </sch:assert>
    </sch:rule>
  </sch:pattern>
</sch:schema>
