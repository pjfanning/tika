/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.pkg;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.UnsupportedFormatException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Parser for Rar files.
 */
public class RarParser implements Parser {
    private static final long serialVersionUID = 6157727985054451501L;

    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(MediaType.application("x-rar-compressed"));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext arg0) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        String mediaType = metadata.get(Metadata.CONTENT_TYPE);

        if (mediaType != null && mediaType.contains("version=5")) {
            throw new UnsupportedFormatException("Tika does not yet support rar version 5.");
        }
        Archive rar = null;
        try (TemporaryResources tmp = new TemporaryResources()) {
            TikaInputStream tis = TikaInputStream.get(stream, tmp, metadata);
            rar = new Archive(tis.getFile());

            if (rar.isEncrypted()) {
                throw new EncryptedDocumentException();
            }

            //Without this BodyContentHandler does not work
            xhtml.element("div", " ");

            FileHeader header = rar.nextFileHeader();
            while (header != null && !Thread.currentThread().isInterrupted()) {
                if (!header.isDirectory()) {
                    Metadata entrydata = PackageParser.handleEntryMetadata(header.getFileName(),
                            header.getCTime(), header.getMTime(), header.getFullUnpackSize(),
                            xhtml);
                    try (TikaInputStream rarTis = TikaInputStream.get(rar.getInputStream(header))) {
                        if (extractor.shouldParseEmbedded(entrydata)) {
                            extractor.parseEmbedded(rarTis, handler, entrydata, true);
                        }
                    }
                }

                header = rar.nextFileHeader();
            }

        } catch (RarException e) {
            throw new TikaException("RarParser Exception", e);
        } finally {
            if (rar != null) {
                rar.close();
            }

        }

        xhtml.endDocument();
    }
}
