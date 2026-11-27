<?xml version="1.0" encoding="UTF-8"?>
<!-- Context-free rules of the synthetic test format; each assertion names its test case in @role -->
<sch:schema xmlns:sch="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt3">
  <sch:ns prefix="m" uri="urn:tst:mini:messages"/>
  <sch:ns prefix="d" uri="urn:tst:mini:dataset"/>
  <sch:ns prefix="i" uri="urn:tst:mini:identifiers"/>

  <sch:let name="successStatus" value="('200')"/>
  <sch:let name="uuidV4" value="'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'"/>

  <sch:pattern id="dataset-id">
    <sch:rule context="/m:upload">
      <sch:assert role="T-DAT-01" test="matches(@datasetId, $uuidV4)">
        The data set ID '<sch:value-of select="@datasetId"/>' is not a UUID version 4.
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <sch:pattern id="status-logic">
    <sch:rule context="/m:dataResponse[@status = $successStatus]">
      <sch:assert role="T-DAT-02" test="exists(d:shipment)">
        A data response with success status '<sch:value-of select="@status"/>' does not carry the data set.
      </sch:assert>
      <sch:assert role="T-DAT-02" test="empty(m:description)">
        A data response with success status '<sch:value-of select="@status"/>' carries an error description.
      </sch:assert>
    </sch:rule>
    <sch:rule context="/m:dataResponse">
      <sch:assert role="T-DAT-02" test="normalize-space(string-join(m:description)) != ''">
        A data response with status '<sch:value-of select="@status"/>' does not carry an error description.
      </sch:assert>
      <sch:assert role="T-DAT-02" test="empty(d:shipment)">
        A data response with status '<sch:value-of select="@status"/>' carries a data set.
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <sch:pattern id="identifier-set">
    <sch:rule context="/m:upload/i:shipment">
      <sch:assert role="T-DAT-03" test="exists(i:mode)">The identifier upload does not contain the mode of transport.</sch:assert>
      <sch:assert role="T-DAT-03" test="exists(i:dangerous)">The identifier upload does not contain the dangerous goods indicator.</sch:assert>
    </sch:rule>
  </sch:pattern>
</sch:schema>
