<!--
    Copyright (C) 2004 Orbeon, Inc.
  
    This program is free software; you can redistribute it and/or modify it under the terms of the
    GNU Lesser General Public License as published by the Free Software Foundation; either version
    2.1 of the License, or (at your option) any later version.
  
    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Lesser General Public License for more details.
  
    The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
-->
<p:config xmlns:p="http://www.orbeon.com/oxf/pipeline"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:oxf="http://www.orbeon.com/oxf/processors"
    xmlns:xhtml="http://www.w3.org/1999/xhtml"
    xmlns:xs="http://www.w3.org/2001/XMLSchema">

    <!-- Extract request body -->
    <p:processor name="oxf:request">
        <p:input name="config">
            <config stream-type="xs:anyURI">
                <include>/request/body</include>
            </config>
        </p:input>
        <p:output name="data" id="request"/>
    </p:processor>

    <!-- Dereference URI and return XML -->
    <p:processor name="oxf:url-generator">
        <p:input name="config" href="aggregate('config', aggregate('url', #request#xpointer(string(/request/body))))"/>
        <p:output name="data" id="xml-request"/>
    </p:processor>

    <p:processor name="oxf:xslt">
        <p:input name="data" href="#xml-request"/>
        <p:input name="config">
            <xsl:stylesheet version="2.0" exclude-result-prefixes="xhtml oxf xs p">
                <xsl:template match="/">
                    <country>
                        <xsl:copy-of select="/country/@us-code"/>
                        <xsl:text>&lt;img src="../images/flags/</xsl:text>
                        <xsl:value-of select="/country/@us-code"/>
                        <xsl:text>-flag.gif"/></xsl:text>
                    </country>
                </xsl:template>
            </xsl:stylesheet>
        </p:input>
        <p:output name="data" id="xml-response"/>
    </p:processor>

    <!-- Generate response -->
    <p:processor name="oxf:xml-serializer">
        <p:input name="data" href="#xml-response"/>
        <p:input name="config">
            <config>
                <content-type>application/xml</content-type>
            </config>
        </p:input>
    </p:processor>

</p:config>
