<?xml version="1.0" encoding="UTF-8"?>
<!--
  Pseudo platform: consignee signature (goods delivered) on a data set of this format. Sets the actual delivery
  date/time (eFTI188, deliveryEvent/actualOccurrenceDateTime, format 205 = CCYYMMDDHHMMZHHMM) to $signatureTime
  and leaves everything else unchanged.
-->
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns="http://efti.eu/v1/consignment/common"
                xpath-default-namespace="http://efti.eu/v1/consignment/common"
                exclude-result-prefixes="xs">

  <xsl:param name="signatureTime" as="xs:dateTime" required="yes"/>

  <xsl:mode on-no-match="shallow-copy"/>

  <xsl:variable name="actualDeliveryDate" as="element()">
    <actualOccurrenceDateTime formatId="205">
      <xsl:value-of select="format-dateTime($signatureTime, '[Y0001][M01][D01][H01][m01][Z0000]')"/>
    </actualOccurrenceDateTime>
  </xsl:variable>

  <!-- actualOccurrenceDateTime is the first element of a TransportEvent -->
  <xsl:template match="/consignment/deliveryEvent">
    <xsl:copy>
      <xsl:apply-templates select="@*"/>
      <xsl:sequence select="$actualDeliveryDate"/>
      <xsl:apply-templates select="node() except actualOccurrenceDateTime"/>
    </xsl:copy>
  </xsl:template>

  <!-- The children of a consignment are declared in code point order of their names, so a new deliveryEvent goes
       before the first child that sorts after it (repeating that child's indentation) -->
  <xsl:template match="/consignment[not(deliveryEvent)]">
    <xsl:variable name="next" select="*[compare(local-name(), 'deliveryEvent') gt 0][1]"/>
    <xsl:variable name="tail" select="$next | $next/following-sibling::node()"/>
    <xsl:copy>
      <xsl:apply-templates select="@*, node() except $tail"/>
      <deliveryEvent>
        <xsl:sequence select="$actualDeliveryDate"/>
      </deliveryEvent>
      <xsl:sequence select="$next/preceding-sibling::node()[1][self::text()]"/>
      <xsl:apply-templates select="$tail"/>
    </xsl:copy>
  </xsl:template>
</xsl:stylesheet>
