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

package com.docdoku.server.http;

import com.docdoku.core.common.BinaryResource;
import com.docdoku.core.document.DocumentIteration;
import com.docdoku.core.document.DocumentMaster;
import com.docdoku.core.document.DocumentMasterKey;
import com.docdoku.core.meta.InstanceAttribute;
import com.docdoku.core.services.IDocumentManagerLocal;
import com.docdoku.server.viewers.DocumentViewer;

import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class DocumentServlet extends HttpServlet {

    @Resource(name = "vaultPath")
    private String vaultPath;

    @EJB
    private IDocumentManagerLocal documentService;
    
    @Override
    protected void doGet(HttpServletRequest pRequest,
                         HttpServletResponse pResponse)
            throws ServletException, IOException {

        try {

            String login = pRequest.getRemoteUser();
            String[] pathInfos = Pattern.compile("/").split(pRequest.getRequestURI());
            int offset;
            if(pRequest.getContextPath().equals(""))
                offset=2;
            else
                offset=3;
            
            String workspaceId = URLDecoder.decode(pathInfos[offset],"UTF-8");
            String docMId = URLDecoder.decode(pathInfos[offset+1],"UTF-8");
            String docMVersion = pathInfos[offset+2];

            DocumentMaster docM = documentService.getDocumentMaster(new DocumentMasterKey(workspaceId, docMId, docMVersion));
            pRequest.setAttribute("docm", docM);

            DocumentIteration doc =  docM.getLastIteration();
            pRequest.setAttribute("attr",  new ArrayList<InstanceAttribute>(doc.getInstanceAttributes().values()));

            String vaultPath = getServletContext().getInitParameter("vaultPath");

            pRequest.setAttribute("vaultPath", vaultPath);

            Set <BinaryResource> attachedFiles = docM.getLastIteration().getAttachedFiles();
            for (BinaryResource attachedFile : attachedFiles) {
                String servletName = selectViewer(attachedFile.getFullName());
                pRequest.setAttribute("attachedFile", attachedFile);
                RequestDispatcher dispatcher = getServletContext().getNamedDispatcher(servletName);
                if (dispatcher != null) {
                    dispatcher.include(pRequest, pResponse);
                }
            }

            pRequest.getRequestDispatcher("/WEB-INF/document.jsp").forward(pRequest, pResponse);

        } catch (Exception pEx) {
            pEx.printStackTrace();
            throw new ServletException("error while fetching your document.", pEx);
        }
    }

    private String selectViewer(String fileFullName) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        String servletName = "";

        Map<String, String> servletsViewer = getServletsViewer();

        for (String className : servletsViewer.keySet()) {
            Class<?> documentViewerImpl = Class.forName(className);
            DocumentViewer documentViewer = (DocumentViewer) documentViewerImpl.newInstance();
            if (documentViewer.canVisualize(fileFullName)) {
                servletName = servletsViewer.get(className);
                break;
            }
        }

        return servletName;
    }

    private Map<String, String> getServletsViewer() {
        Map<String, String> servletsViewer = new HashMap<String, String>();

        Map<String, ? extends ServletRegistration> servletRegistrations = getServletContext().getServletRegistrations();

        for (Map.Entry<String, ? extends ServletRegistration> entry : servletRegistrations.entrySet()) {
            String documentViewerParam = entry.getValue().getInitParameter("DOCUMENT_VIEWER");
            if (documentViewerParam != null) {
                servletsViewer.put(documentViewerParam, entry.getKey());
            }
        }

        return servletsViewer;
    }

}
