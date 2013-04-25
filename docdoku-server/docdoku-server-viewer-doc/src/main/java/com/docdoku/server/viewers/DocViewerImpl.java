/*
 * DocDoku, Professional Open Source
 * Copyright 2006 - 2013 DocDoku SARL
 *
 * This file is part of DocDokuPLM.
 *
 * DocDokuPLM is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * DocDokuPLM is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with DocDokuPLM.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.docdoku.server.viewers;

import com.docdoku.core.common.BinaryResource;
import com.docdoku.core.services.IDataManagerLocal;
import com.docdoku.core.util.FileIO;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.google.common.io.ByteStreams;

import javax.ejb.EJB;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DocViewerImpl implements DocumentViewer {

    @EJB
    private IDataManagerLocal dataManager;

    @EJB
    private FileConverter fileConverter;

    @Override
    public boolean canPrepareFileForViewer(BinaryResource binaryResource, HttpServletRequest pRequest) {
        return FileIO.isDocFile(binaryResource.getName()) && hasValidOutput(pRequest);
    }

    private boolean hasValidOutput(HttpServletRequest pRequest) {
        String output = pRequest.getParameter("output");
        return output != null && output.equals("pdf");
    }

    @Override
    public InputStream prepareFileForViewer(HttpServletRequest pRequest, HttpServletResponse pResponse, ServletContext servletContext, final BinaryResource binaryResource) throws Exception {

        String output = pRequest.getParameter("output");

        String extension = FileIO.getExtension(binaryResource.getName());

        InputStream inputStream = null;

        if ("pdf".equals(output)) {

            pResponse.setContentType("application/pdf");

            if (extension.equals("pdf")) {
                inputStream = dataManager.getBinaryResourceInputStream(binaryResource);
            } else {
                String subResourceVirtualPath = FileIO.getFileNameWithoutExtension(binaryResource.getName()) + ".pdf";
                if (dataManager.exists(binaryResource, subResourceVirtualPath) &&
                        dataManager.getLastModified(binaryResource, subResourceVirtualPath).after(binaryResource.getLastModified())) {
                    //if the resource is already converted, return it
                    inputStream = dataManager.getBinarySubResourceInputStream(binaryResource, subResourceVirtualPath);
                } else {
                    InputStream inputStreamConverted = fileConverter.convertToPDF(binaryResource.getName(), dataManager.getBinaryResourceInputStream(binaryResource));
                    //copy the converted file for further reuse
                    OutputStream outputStream = dataManager.getBinarySubResourceOutputStream(binaryResource, subResourceVirtualPath);
                    try {
                        ByteStreams.copy(inputStreamConverted, outputStream);
                    } finally {
                        inputStreamConverted.close();
                        outputStream.flush();
                        outputStream.close();
                    }
                    inputStream = dataManager.getBinarySubResourceInputStream(binaryResource, subResourceVirtualPath);
                }
            }
        }

        return inputStream;
    }

    @Override
    public boolean canRenderViewerTemplate(BinaryResource binaryResource) {
        return FileIO.isDocFile(binaryResource.getName());
    }

    @Override
    public String renderHtmlForViewer(BinaryResource docResource) throws Exception {
        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile("com/docdoku/server/viewers/document_viewer.mustache");
        Map<String, Object> scopes = new HashMap<>();
        scopes.put("uriResource", ViewerUtils.getURI(docResource));
        scopes.put("fileName", docResource.getName());
        scopes.put("thisId", UUID.randomUUID().toString());
        StringWriter templateWriter = new StringWriter();
        mustache.execute(templateWriter, scopes).flush();
        return templateWriter.toString();
    }

}
