/*
 * DocDoku, Professional Open Source
 * Copyright 2006 - 2014 DocDoku SARL
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
package com.docdoku.server.rest;

import com.docdoku.core.document.DocumentMasterTemplate;
import com.docdoku.core.document.DocumentMasterTemplateKey;
import com.docdoku.core.exceptions.ApplicationException;
import com.docdoku.core.meta.InstanceAttributeTemplate;
import com.docdoku.core.security.UserGroupMapping;
import com.docdoku.core.services.IDocumentManagerLocal;
import com.docdoku.server.rest.dto.DocumentMasterTemplateDTO;
import com.docdoku.server.rest.dto.DocumentTemplateCreationDTO;
import com.docdoku.server.rest.dto.InstanceAttributeTemplateDTO;
import com.docdoku.server.rest.dto.TemplateGeneratedIdDTO;
import org.dozer.DozerBeanMapperSingletonWrapper;
import org.dozer.Mapper;

import javax.annotation.PostConstruct;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 *
 * @author Yassine Belouad
 */
@Stateless
@DeclareRoles(UserGroupMapping.REGULAR_USER_ROLE_ID)
@RolesAllowed(UserGroupMapping.REGULAR_USER_ROLE_ID)
public class DocumentTemplateResource {

    @EJB
    private IDocumentManagerLocal documentService;
    private Mapper mapper;

    public DocumentTemplateResource() {
    }

    @PostConstruct
    public void init() {
        mapper = DozerBeanMapperSingletonWrapper.getInstance();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DocumentMasterTemplateDTO[] getDocumentMasterTemplates(@PathParam("workspaceId") String workspaceId) {
        try {

            DocumentMasterTemplate[] docMsTemplates = documentService.getDocumentMasterTemplates(workspaceId);
            DocumentMasterTemplateDTO[] dtos = new DocumentMasterTemplateDTO[docMsTemplates.length];

            for (int i = 0; i < docMsTemplates.length; i++) {
                dtos[i] = mapper.map(docMsTemplates[i], DocumentMasterTemplateDTO.class);
            }

            return dtos;

        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }

    @GET
    @Path("{templateId}")
    @Produces(MediaType.APPLICATION_JSON)
    public DocumentMasterTemplateDTO getDocumentMasterTemplates(@PathParam("workspaceId") String workspaceId, @PathParam("templateId") String templateId) {
        try {

            DocumentMasterTemplate docMsTemplate = documentService.getDocumentMasterTemplate(new DocumentMasterTemplateKey(workspaceId, templateId));
            DocumentMasterTemplateDTO dto = mapper.map(docMsTemplate, DocumentMasterTemplateDTO.class);

            return dto;

        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }
    
    @GET
    @Path("{templateId}/generate_id")
    @Produces(MediaType.APPLICATION_JSON)
    public TemplateGeneratedIdDTO generateDocMsId(@PathParam("workspaceId") String workspaceId, @PathParam("templateId") String templateId) {
        try {

            String generateId = documentService.generateId(workspaceId, templateId);
            return new TemplateGeneratedIdDTO(generateId);

        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DocumentMasterTemplateDTO createDocumentMasterTemplate(@PathParam("workspaceId") String workspaceId, DocumentTemplateCreationDTO templateCreationDTO) {

        try {      
            String id =templateCreationDTO.getReference();
            String documentType = templateCreationDTO.getDocumentType();
            String mask = templateCreationDTO.getMask();
            boolean idGenerated = templateCreationDTO.isIdGenerated();
            boolean attributesLocked = templateCreationDTO.isAttributesLocked();

            Set<InstanceAttributeTemplateDTO> attributeTemplates = templateCreationDTO.getAttributeTemplates();
            List<InstanceAttributeTemplateDTO> attributeTemplatesList = new ArrayList<InstanceAttributeTemplateDTO>(attributeTemplates);
            InstanceAttributeTemplateDTO[] attributeTemplatesDtos = new InstanceAttributeTemplateDTO[attributeTemplatesList.size()];

            for (int i = 0; i < attributeTemplatesDtos.length; i++) {
                attributeTemplatesDtos[i] = attributeTemplatesList.get(i);
            }

            DocumentMasterTemplate template = documentService.createDocumentMasterTemplate(workspaceId, id, documentType, mask, createInstanceAttributeTemplateFromDto(attributeTemplatesDtos), idGenerated, attributesLocked);
            DocumentMasterTemplateDTO templateDto = mapper.map(template, DocumentMasterTemplateDTO.class);

            return templateDto;

        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }
    
    @PUT
    @Path("{templateId}") 
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DocumentMasterTemplateDTO updateDocMsTemplate(@PathParam("workspaceId") String workspaceId,@PathParam("templateId") String templateId, DocumentMasterTemplateDTO docMsTemplateDTO) {

        try {
            String id = docMsTemplateDTO.getId();
            String documentType = docMsTemplateDTO.getDocumentType();
            String mask = docMsTemplateDTO.getMask();
            boolean idGenerated = docMsTemplateDTO.isIdGenerated();
            boolean attributesLocked = docMsTemplateDTO.isAttributesLocked();

            Set<InstanceAttributeTemplateDTO> attributeTemplates = docMsTemplateDTO.getAttributeTemplates();
            List<InstanceAttributeTemplateDTO> attributeTemplatesList = new ArrayList<InstanceAttributeTemplateDTO>(attributeTemplates);
            InstanceAttributeTemplateDTO[] attributeTemplatesDtos = new InstanceAttributeTemplateDTO[attributeTemplatesList.size()];

            for (int i = 0; i < attributeTemplatesDtos.length; i++) {
                attributeTemplatesDtos[i] = attributeTemplatesList.get(i);
            }

            DocumentMasterTemplate template = documentService.updateDocumentMasterTemplate(new DocumentMasterTemplateKey(workspaceId, templateId), documentType, mask, createInstanceAttributeTemplateFromDto(attributeTemplatesDtos), idGenerated, attributesLocked);
            DocumentMasterTemplateDTO templateDto = mapper.map(template, DocumentMasterTemplateDTO.class);

            return templateDto;

        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }

    @DELETE
    @Path("{templateId}")
    public Response deleteDocumentMasterTemplate(@PathParam("workspaceId") String workspaceId, @PathParam("templateId") String templateId) {
        try {
            documentService.deleteDocumentMasterTemplate(new DocumentMasterTemplateKey(workspaceId, templateId));
            return Response.ok().build();
        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }

    @DELETE
    @Path("{templateId}/files/{fileName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response removeAttachedFile(@PathParam("workspaceId") String workspaceId, @PathParam("templateId") String templateId, @PathParam("fileName") String fileName) {
        try {
            String fileFullName = workspaceId + "/document-templates/" + templateId + "/" + fileName;

            documentService.removeFileFromTemplate(fileFullName);
            return Response.ok().build();

        } catch (ApplicationException ex) {
            throw new RestApiException(ex.toString(), ex.getMessage());
        }
    }

    private InstanceAttributeTemplate[] createInstanceAttributeTemplateFromDto(InstanceAttributeTemplateDTO[] dtos) {
        InstanceAttributeTemplate[] data = new InstanceAttributeTemplate[dtos.length];

        for (int i = 0; i < dtos.length; i++) {
            data[i] = createInstanceAttributeTemplateObject(dtos[i]);
        }

        return data;
    }

    private InstanceAttributeTemplate createInstanceAttributeTemplateObject(InstanceAttributeTemplateDTO instanceAttributeTemplateDTO) {
        InstanceAttributeTemplate data = new InstanceAttributeTemplate();
        data.setName(instanceAttributeTemplateDTO.getName());
        data.setAttributeType(InstanceAttributeTemplate.AttributeType.valueOf(instanceAttributeTemplateDTO.getAttributeType().name()));
        data.setMandatory(instanceAttributeTemplateDTO.isMandatory());
        return data;
    }
}