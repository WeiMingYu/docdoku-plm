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
package com.docdoku.server;

import com.docdoku.core.common.*;
import com.docdoku.core.configuration.*;
import com.docdoku.core.document.DocumentIteration;
import com.docdoku.core.document.DocumentIterationKey;
import com.docdoku.core.document.DocumentLink;
import com.docdoku.core.exceptions.*;
import com.docdoku.core.meta.InstanceAttribute;
import com.docdoku.core.meta.InstanceAttributeTemplate;
import com.docdoku.core.meta.InstanceNumberAttribute;
import com.docdoku.core.product.*;
import com.docdoku.core.product.PartIteration.Source;
import com.docdoku.core.query.PartSearchQuery;
import com.docdoku.core.security.ACL;
import com.docdoku.core.security.ACLUserEntry;
import com.docdoku.core.security.ACLUserGroupEntry;
import com.docdoku.core.services.*;
import com.docdoku.core.sharing.SharedEntityKey;
import com.docdoku.core.sharing.SharedPart;
import com.docdoku.core.util.NamingConvention;
import com.docdoku.core.util.Tools;
import com.docdoku.core.workflow.*;
import com.docdoku.server.dao.*;
import com.docdoku.server.esindexer.ESIndexer;

import javax.annotation.Resource;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.ejb.EJB;
import javax.ejb.Local;
import javax.ejb.SessionContext;
import javax.ejb.Stateless;
import javax.jws.WebService;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@DeclareRoles({"users","admin","guest-proxy"})
@Local(IProductManagerLocal.class)
@Stateless(name = "ProductManagerBean")
@WebService(endpointInterface = "com.docdoku.core.services.IProductManagerWS")
public class ProductManagerBean implements IProductManagerWS, IProductManagerLocal {

    @PersistenceContext
    private EntityManager em;
    @Resource
    private SessionContext ctx;
    @EJB
    private IMailerLocal mailer;
    @EJB
    private IUserManagerLocal userManager;
    @EJB
    private IDataManagerLocal dataManager;
    @EJB
    private ESIndexer esIndexer;

    private static final Logger LOGGER = Logger.getLogger(ProductManagerBean.class.getName());

    @RolesAllowed("users")
    @Override
    public List<PartUsageLink[]> findPartUsages(ConfigurationItemKey pKey, PartMasterKey pPartMKey) throws WorkspaceNotFoundException, UserNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pKey.getWorkspace());
        PartUsageLinkDAO linkDAO = new PartUsageLinkDAO(new Locale(user.getLanguage()), em);
        List<PartUsageLink[]> usagePaths = linkDAO.findPartUsagePaths(pPartMKey);
        //TODO filter by configuration item
        return usagePaths;
    }

    @RolesAllowed("users")
    @Override
    public List<PartMaster> findPartMasters(String pWorkspaceId, String pPartNumber, int pMaxResults) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException {
        User user = userManager.checkWorkspaceWriteAccess(pWorkspaceId);
        PartMasterDAO partMDAO = new PartMasterDAO(new Locale(user.getLanguage()), em);
        return partMDAO.findPartMasters(pWorkspaceId, pPartNumber, pMaxResults);
    }

    @RolesAllowed("users")
    @Override
    public PartUsageLink filterProductStructure(ConfigurationItemKey pKey, ConfigSpec configSpec, Integer partUsageLink, Integer depth) throws ConfigurationItemNotFoundException, WorkspaceNotFoundException, NotAllowedException, UserNotFoundException, UserNotActiveException, PartUsageLinkNotFoundException, AccessRightException {
        User user = userManager.checkWorkspaceReadAccess(pKey.getWorkspace());
        PartUsageLink rootUsageLink;

        if (partUsageLink == null || partUsageLink == -1) {
            ConfigurationItem ci = new ConfigurationItemDAO(new Locale(user.getLanguage()), em).loadConfigurationItem(pKey);
            rootUsageLink = new PartUsageLink();
            rootUsageLink.setId(-1);
            rootUsageLink.setAmount(1d);
            List<CADInstance> cads = new ArrayList<>();
            CADInstance cad = new CADInstance(0d, 0d, 0d, 0d, 0d, 0d);
            cad.setId(-1);
            cads.add(cad);
            rootUsageLink.setCadInstances(cads);
            rootUsageLink.setComponent(ci.getDesignItem());
        } else {
            rootUsageLink = new PartUsageLinkDAO(new Locale(user.getLanguage()), em).loadPartUsageLink(partUsageLink);
        }

        PartMaster component = rootUsageLink.getComponent();

        if(component.getWorkspaceId().equals(pKey.getWorkspace())){

            depth = (depth == null) ? -1 : depth;

            if(configSpec != null){
                filterConfigSpec(configSpec,rootUsageLink.getComponent(),depth, user);
            }else{
                filterConfigSpec(new LatestConfigSpec(user),rootUsageLink.getComponent(),depth, user);
            }

            return rootUsageLink;
        }else{
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }
    }

    private void filterConfigSpec(ConfigSpec configSpec, PartMaster partMaster, int depth, User user){
        PartIteration partI = configSpec.filterConfigSpec(partMaster);
        boolean canRead = true;

        if(partI!=null){
            PartRevision partR = partI.getPartRevision();

            try {
                checkPartRevisionReadAccess(partR.getKey());
                int numberOfIteration = getNumberOfIteration(partR.getKey());
                if ((partR.isCheckedOut()) && (!partR.getCheckOutUser().equals(user)) && partI.getIteration()==numberOfIteration) {
                    canRead = false;
                }
            } catch (UserNotFoundException | UserNotActiveException | WorkspaceNotFoundException | PartRevisionNotFoundException |AccessRightException ignored) {
                canRead = false;
            }

            if (canRead && depth != 0) {
                depth--;
                for (PartUsageLink usageLink : partI.getComponents()) {
                    filterConfigSpec(configSpec, usageLink.getComponent(), depth, user);

                    for (PartSubstituteLink subLink : usageLink.getSubstitutes()) {
                        filterConfigSpec(configSpec, subLink.getSubstitute(), 0, user);
                    }
                }
            }
        }

        for (PartAlternateLink alternateLink : partMaster.getAlternates()) {
            filterConfigSpec(configSpec,alternateLink.getAlternate(), 0, user);
        }

        em.detach(partMaster);

        if(partI!=null){
            PartRevision partRevision = partI.getPartRevision();
            if (partMaster.getPartRevisions().size() > 1) {
                partMaster.getPartRevisions().retainAll(Collections.singleton(partRevision));
            }
            if (partRevision != null && partRevision.getNumberOfIterations() > 1) {
                partRevision.getPartIterations().retainAll(Collections.singleton(partI));
            }
            if(!canRead){
                partI.getComponents().clear();
            }
        }
    }

    @RolesAllowed("users")
    @Override
    public ConfigurationItem createConfigurationItem(String pWorkspaceId, String pId, String pDescription, String pDesignItemNumber) throws UserNotFoundException, WorkspaceNotFoundException, AccessRightException, NotAllowedException, ConfigurationItemAlreadyExistsException, CreationException, PartMasterNotFoundException {

        User user = userManager.checkWorkspaceWriteAccess(pWorkspaceId);
        if (!NamingConvention.correct(pId)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException9");
        }

        ConfigurationItem ci = new ConfigurationItem(user.getWorkspace(), pId, pDescription);

        try {
            PartMaster designedPartMaster = new PartMasterDAO(new Locale(user.getLanguage()), em).loadPartM(new PartMasterKey(pWorkspaceId, pDesignItemNumber));
            ci.setDesignItem(designedPartMaster);
            new ConfigurationItemDAO(new Locale(user.getLanguage()), em).createConfigurationItem(ci);
            return ci;
        } catch (PartMasterNotFoundException e) {
            LOGGER.log(Level.FINEST,null,e);
            throw new PartMasterNotFoundException(new Locale(user.getLanguage()),pDesignItemNumber);
        }

    }

    @RolesAllowed("users")
    @Override
    public PartMaster createPartMaster(String pWorkspaceId, String pNumber, String pName, boolean pStandardPart, String pWorkflowModelId, String pPartRevisionDescription, String templateId, Map<String, String> roleMappings, ACLUserEntry[] pACLUserEntries, ACLUserGroupEntry[] pACLUserGroupEntries) throws NotAllowedException, UserNotFoundException, WorkspaceNotFoundException, AccessRightException, WorkflowModelNotFoundException, PartMasterAlreadyExistsException, CreationException, PartMasterTemplateNotFoundException, FileAlreadyExistsException, RoleNotFoundException {

        User user = userManager.checkWorkspaceWriteAccess(pWorkspaceId);
        if (!NamingConvention.correct(pNumber)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException9");
        }

        PartMaster pm = new PartMaster(user.getWorkspace(), pNumber, user);
        pm.setName(pName);
        pm.setStandardPart(pStandardPart);
        Date now = new Date();
        pm.setCreationDate(now);
        PartRevision newRevision = pm.createNextRevision(user);

        if (pWorkflowModelId != null) {

            UserDAO userDAO = new UserDAO(new Locale(user.getLanguage()),em);
            RoleDAO roleDAO = new RoleDAO(new Locale(user.getLanguage()),em);

            Map<Role,User> roleUserMap = new HashMap<>();

            for (Object o : roleMappings.entrySet()) {
                Map.Entry pairs = (Map.Entry) o;
                String roleName = (String) pairs.getKey();
                String userLogin = (String) pairs.getValue();
                User worker = userDAO.loadUser(new UserKey(user.getWorkspaceId(), userLogin));
                Role role = roleDAO.loadRole(new RoleKey(user.getWorkspaceId(), roleName));
                roleUserMap.put(role, worker);
            }

            WorkflowModel workflowModel = new WorkflowModelDAO(new Locale(user.getLanguage()), em).loadWorkflowModel(new WorkflowModelKey(user.getWorkspaceId(), pWorkflowModelId));
            Workflow workflow = workflowModel.createWorkflow(roleUserMap);
            newRevision.setWorkflow(workflow);

            Collection<Task> runningTasks = workflow.getRunningTasks();
            for (Task runningTask : runningTasks) {
                runningTask.start();
            }

            mailer.sendApproval(runningTasks, newRevision);

        }
        newRevision.setCheckOutUser(user);
        newRevision.setCheckOutDate(now);
        newRevision.setCreationDate(now);
        newRevision.setDescription(pPartRevisionDescription);
        PartIteration ite = newRevision.createNextIteration(user);
        ite.setCreationDate(now);

        if(templateId != null){

            PartMasterTemplate partMasterTemplate = new PartMasterTemplateDAO(new Locale(user.getLanguage()),em).loadPartMTemplate(new PartMasterTemplateKey(pWorkspaceId,templateId));
            pm.setType(partMasterTemplate.getPartType());
            pm.setAttributesLocked(partMasterTemplate.isAttributesLocked());

            Map<String, InstanceAttribute> attrs = new HashMap<>();
            for (InstanceAttributeTemplate attrTemplate : partMasterTemplate.getAttributeTemplates()) {
                InstanceAttribute attr = attrTemplate.createInstanceAttribute();
                attrs.put(attr.getName(), attr);
            }
            ite.setInstanceAttributes(attrs);

            BinaryResourceDAO binDAO = new BinaryResourceDAO(new Locale(user.getLanguage()), em);
            BinaryResource sourceFile = partMasterTemplate.getAttachedFile();

            if(sourceFile != null){
                String fileName = sourceFile.getName();
                long length = sourceFile.getContentLength();
                Date lastModified = sourceFile.getLastModified();
                String fullName = pWorkspaceId + "/parts/" + pm.getNumber() + "/A/1/nativecad/" + fileName;
                BinaryResource targetFile = new BinaryResource(fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                ite.setNativeCADFile(targetFile);
                try {
                    dataManager.copyData(sourceFile, targetFile);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }

        }

        if ((pACLUserEntries != null && pACLUserEntries.length > 0) || (pACLUserGroupEntries != null && pACLUserGroupEntries.length > 0)) {
            ACL acl = new ACL();
            if (pACLUserEntries != null) {
                for (ACLUserEntry entry : pACLUserEntries) {
                    acl.addEntry(em.getReference(User.class, new UserKey(user.getWorkspaceId(), entry.getPrincipalLogin())), entry.getPermission());
                }
            }

            if (pACLUserGroupEntries != null) {
                for (ACLUserGroupEntry entry : pACLUserGroupEntries) {
                    acl.addEntry(em.getReference(UserGroup.class, new UserGroupKey(user.getWorkspaceId(), entry.getPrincipalId())), entry.getPermission());
                }
            }
            newRevision.setACL(acl);
            new ACLDAO(em).createACL(acl);
        }

        new PartMasterDAO(new Locale(user.getLanguage()), em).createPartM(pm);
        return pm;
    }

    @RolesAllowed("users")
    @Override
    public PartRevision undoCheckOutPart(PartRevisionKey pPartRPK) throws NotAllowedException, PartRevisionNotFoundException, UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, AccessRightException {
        User user = userManager.checkWorkspaceReadAccess(pPartRPK.getPartMaster().getWorkspace());
        PartRevisionDAO partRDAO = new PartRevisionDAO(new Locale(user.getLanguage()), em);
        PartRevision partR = partRDAO.loadPartR(pPartRPK);

        //Check access rights on partR
        Workspace wks = new WorkspaceDAO(new Locale(user.getLanguage()), em).loadWorkspace(pPartRPK.getPartMaster().getWorkspace());
        boolean isAdmin = wks.getAdmin().getLogin().equals(user.getLogin());

        if (!isAdmin && partR.getACL() != null && !partR.getACL().hasWriteAccess(user)) {
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }


        if (partR.isCheckedOut() && partR.getCheckOutUser().equals(user)) {
            if(partR.getLastIteration().getIteration() <= 1) {
                throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException41");
            }
            PartIteration partIte = partR.removeLastIteration();
            for (Geometry file : partIte.getGeometries()) {
                try {
                    dataManager.deleteData(file);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }

            for (BinaryResource file : partIte.getAttachedFiles()) {
                try {
                    dataManager.deleteData(file);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }

            BinaryResource nativeCAD = partIte.getNativeCADFile();
            if (nativeCAD != null) {
                try {
                    dataManager.deleteData(nativeCAD);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }

            PartIterationDAO partIDAO = new PartIterationDAO(em);
            partIDAO.removeIteration(partIte);
            partR.setCheckOutDate(null);
            partR.setCheckOutUser(null);
            return partR;
        } else {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException19");
        }
    }

    @RolesAllowed("users")
    @Override
    public PartRevision checkOutPart(PartRevisionKey pPartRPK) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, PartRevisionNotFoundException, NotAllowedException, FileAlreadyExistsException, CreationException {
        User user = userManager.checkWorkspaceWriteAccess(pPartRPK.getPartMaster().getWorkspace());
        PartRevisionDAO partRDAO = new PartRevisionDAO(new Locale(user.getLanguage()), em);
        PartRevision partR = partRDAO.loadPartR(pPartRPK);
        //Check access rights on partR
        Workspace wks = new WorkspaceDAO(new Locale(user.getLanguage()), em).loadWorkspace(pPartRPK.getPartMaster().getWorkspace());
        boolean isAdmin = wks.getAdmin().getLogin().equals(user.getLogin());

        if (!isAdmin && partR.getACL() != null && !partR.getACL().hasWriteAccess(user)) {
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }

        if (partR.isCheckedOut()) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException37");
        }

        if (partR.isReleased()) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException11");
        }

        PartIteration beforeLastPartIteration = partR.getLastIteration();

        PartIteration newPartIteration = partR.createNextIteration(user);
        //We persist the doc as a workaround for a bug which was introduced
        //since glassfish 3 that set the DTYPE to null in the instance attribute table
        em.persist(newPartIteration);
        Date now = new Date();
        newPartIteration.setCreationDate(now);
        partR.setCheckOutUser(user);
        partR.setCheckOutDate(now);

        if (beforeLastPartIteration != null) {
            BinaryResourceDAO binDAO = new BinaryResourceDAO(new Locale(user.getLanguage()), em);
            for (BinaryResource sourceFile : beforeLastPartIteration.getAttachedFiles()) {
                String fileName = sourceFile.getName();
                long length = sourceFile.getContentLength();
                Date lastModified = sourceFile.getLastModified();
                String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/" + newPartIteration.getIteration() + "/" + fileName;
                BinaryResource targetFile = new BinaryResource(fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                newPartIteration.addFile(targetFile);
            }

            List<PartUsageLink> components = new LinkedList<>();
            for (PartUsageLink usage : beforeLastPartIteration.getComponents()) {
                PartUsageLink newUsage = usage.clone();
                components.add(newUsage);
            }
            newPartIteration.setComponents(components);

            for (Geometry sourceFile : beforeLastPartIteration.getGeometries()) {
                String fileName = sourceFile.getName();
                long length = sourceFile.getContentLength();
                int quality = sourceFile.getQuality();
                Date lastModified = sourceFile.getLastModified();
                String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/" + newPartIteration.getIteration() + "/" + fileName;
                Geometry targetFile = new Geometry(quality, fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                newPartIteration.addGeometry(targetFile);
            }

            BinaryResource nativeCADFile = beforeLastPartIteration.getNativeCADFile();
            if (nativeCADFile != null) {
                String fileName = nativeCADFile.getName();
                long length = nativeCADFile.getContentLength();
                Date lastModified = nativeCADFile.getLastModified();
                String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/" + newPartIteration.getIteration() + "/nativecad/" + fileName;
                BinaryResource targetFile = new BinaryResource(fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                newPartIteration.setNativeCADFile(targetFile);
            }

            Set<DocumentLink> links = new HashSet<>();
            for (DocumentLink link : beforeLastPartIteration.getLinkedDocuments()) {
                DocumentLink newLink = link.clone();
                links.add(newLink);
            }
            newPartIteration.setLinkedDocuments(links);

            InstanceAttributeDAO attrDAO = new InstanceAttributeDAO(em);
            Map<String, InstanceAttribute> attrs = new HashMap<>();
            for (InstanceAttribute attr : beforeLastPartIteration.getInstanceAttributes().values()) {
                InstanceAttribute newAttr = attr.clone();
                //Workaround for the NULL DTYPE bug
                attrDAO.createAttribute(newAttr);
                attrs.put(newAttr.getName(), newAttr);
            }
            newPartIteration.setInstanceAttributes(attrs);
        }

        return partR;
    }

    @RolesAllowed("users")
    @Override
    public PartRevision checkInPart(PartRevisionKey pPartRPK) throws PartRevisionNotFoundException, UserNotFoundException, WorkspaceNotFoundException, AccessRightException, NotAllowedException, ESServerException {
        User user = userManager.checkWorkspaceWriteAccess(pPartRPK.getPartMaster().getWorkspace());
        PartRevisionDAO partRDAO = new PartRevisionDAO(new Locale(user.getLanguage()), em);
        PartRevision partR = partRDAO.loadPartR(pPartRPK);

        //Check access rights on partR
        Workspace wks = new WorkspaceDAO(new Locale(user.getLanguage()), em).loadWorkspace(pPartRPK.getPartMaster().getWorkspace());
        boolean isAdmin = wks.getAdmin().getLogin().equals(user.getLogin());

        if (!isAdmin && partR.getACL() != null && !partR.getACL().hasWriteAccess(user)) {
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }

        if (partR.isCheckedOut() && partR.getCheckOutUser().equals(user)) {
            partR.setCheckOutDate(null);
            partR.setCheckOutUser(null);

            for(PartIteration partIteration : partR.getPartIterations()){
                esIndexer.index(partIteration);                                                                         // Index all iterations in ElasticSearch (decrease old iteration boost factor)
            }
            return partR;
        } else {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException20");
        }
    }

    @RolesAllowed({"users","guest-proxy"})
    @Override
    public BinaryResource getBinaryResource(String pFullName) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, FileNotFoundException, NotAllowedException {

        if(ctx.isCallerInRole("guest-proxy")){
            return new BinaryResourceDAO(em).loadBinaryResource(pFullName);
        }

        User user = userManager.checkWorkspaceReadAccess(BinaryResource.parseWorkspaceId(pFullName));
        Locale userLocale = new Locale(user.getLanguage());
        BinaryResourceDAO binDAO = new BinaryResourceDAO(userLocale, em);
        BinaryResource binaryResource = binDAO.loadBinaryResource(pFullName);

        PartIteration partIte = binDAO.getPartOwner(binaryResource);
        if (partIte != null) {
            PartRevision partR = partIte.getPartRevision();

            if (partR.isCheckedOut() && !partR.getCheckOutUser().equals(user) && partR.getLastIteration().equals(partIte)) {
                throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException34");
            } else {
                return binaryResource;
            }
        } else {
            throw new FileNotFoundException(userLocale, pFullName);
        }
    }

    @RolesAllowed("users")
    @Override
    public BinaryResource saveGeometryInPartIteration(PartIterationKey pPartIPK, String pName, int quality, long pSize, double radius) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, NotAllowedException, PartRevisionNotFoundException, FileAlreadyExistsException, CreationException {
        User user = userManager.checkWorkspaceReadAccess(pPartIPK.getWorkspaceId());
        if (!NamingConvention.correctNameFile(pName)) {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException9");
        }

        PartRevisionDAO partRDAO = new PartRevisionDAO(em);
        PartRevision partR = partRDAO.loadPartR(pPartIPK.getPartRevision());
        PartIteration partI = partR.getIteration(pPartIPK.getIteration());
        if (partR.isCheckedOut() && partR.getCheckOutUser().equals(user) && partR.getLastIteration().equals(partI)) {
            Geometry geometryBinaryResource = null;
            String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/" + partI.getIteration() + "/" + pName;

            for (Geometry geo : partI.getGeometries()) {
                if (geo.getFullName().equals(fullName)) {
                    geometryBinaryResource = geo;
                    break;
                }
            }
            if (geometryBinaryResource == null) {
                geometryBinaryResource = new Geometry(quality, fullName, pSize, new Date(), radius);
                new BinaryResourceDAO(em).createBinaryResource(geometryBinaryResource);
                partI.addGeometry(geometryBinaryResource);
            } else {
                geometryBinaryResource.setContentLength(pSize);
                geometryBinaryResource.setQuality(quality);
                geometryBinaryResource.setLastModified(new Date());
                geometryBinaryResource.setRadius(radius);
            }
            return geometryBinaryResource;
        } else {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException4");
        }
    }

    @RolesAllowed("users")
    @Override
    public BinaryResource saveNativeCADInPartIteration(PartIterationKey pPartIPK, String pName, long pSize) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, NotAllowedException, PartRevisionNotFoundException, FileAlreadyExistsException, CreationException {
        User user = userManager.checkWorkspaceReadAccess(pPartIPK.getWorkspaceId());
        if (!NamingConvention.correctNameFile(pName)) {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException9");
        }

        BinaryResourceDAO binDAO = new BinaryResourceDAO(new Locale(user.getLanguage()), em);
        PartRevisionDAO partRDAO = new PartRevisionDAO(em);
        PartRevision partR = partRDAO.loadPartR(pPartIPK.getPartRevision());
        PartIteration partI = partR.getIteration(pPartIPK.getIteration());
        if (partR.isCheckedOut() && partR.getCheckOutUser().equals(user) && partR.getLastIteration().equals(partI)) {
            String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/" + partI.getIteration() + "/nativecad/" + pName;
            BinaryResource nativeCADBinaryResource = partI.getNativeCADFile();
            if (nativeCADBinaryResource == null) {
                nativeCADBinaryResource = new BinaryResource(fullName, pSize, new Date());
                binDAO.createBinaryResource(nativeCADBinaryResource);
                partI.setNativeCADFile(nativeCADBinaryResource);
            } else if (nativeCADBinaryResource.getFullName().equals(fullName)) {
                nativeCADBinaryResource.setContentLength(pSize);
                nativeCADBinaryResource.setLastModified(new Date());
            } else {

                try {
                    dataManager.deleteData(nativeCADBinaryResource);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
                partI.setNativeCADFile(null);
                binDAO.removeBinaryResource(nativeCADBinaryResource);
                //Delete converted files if any
                List<Geometry> geometries = new ArrayList<>(partI.getGeometries());
                for(Geometry geometry : geometries){
                    try {
                        dataManager.deleteData(geometry);
                    } catch (StorageException e) {
                        LOGGER.log(Level.INFO, null, e);
                    }
                    partI.removeGeometry(geometry);
                }
                Set<BinaryResource> attachedFiles = new HashSet<>(partI.getAttachedFiles());
                for(BinaryResource attachedFile : attachedFiles){
                    try {
                        dataManager.deleteData(attachedFile);
                    } catch (StorageException e) {
                        LOGGER.log(Level.INFO, null, e);
                    }
                    partI.removeFile(attachedFile);
                }

                nativeCADBinaryResource = new BinaryResource(fullName, pSize, new Date());
                binDAO.createBinaryResource(nativeCADBinaryResource);
                partI.setNativeCADFile(nativeCADBinaryResource);
            }
            return nativeCADBinaryResource;
        } else {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException4");
        }

    }

    @RolesAllowed("users")
    @Override
    public BinaryResource saveFileInPartIteration(PartIterationKey pPartIPK, String pName, long pSize) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, NotAllowedException, PartRevisionNotFoundException, FileAlreadyExistsException, CreationException {
        User user = userManager.checkWorkspaceReadAccess(pPartIPK.getWorkspaceId());
        if (!NamingConvention.correctNameFile(pName)) {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException9");
        }

        PartRevisionDAO partRDAO = new PartRevisionDAO(em);
        PartRevision partR = partRDAO.loadPartR(pPartIPK.getPartRevision());
        PartIteration partI = partR.getIteration(pPartIPK.getIteration());
        if (partR.isCheckedOut() && partR.getCheckOutUser().equals(user) && partR.getLastIteration().equals(partI)) {
            BinaryResource binaryResource = null;
            String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/" + partI.getIteration() + "/" + pName;

            for (BinaryResource bin : partI.getAttachedFiles()) {
                if (bin.getFullName().equals(fullName)) {
                    binaryResource = bin;
                    break;
                }
            }
            if (binaryResource == null) {
                binaryResource = new BinaryResource(fullName, pSize, new Date());
                new BinaryResourceDAO(em).createBinaryResource(binaryResource);
                partI.addFile(binaryResource);
            } else {
                binaryResource.setContentLength(pSize);
                binaryResource.setLastModified(new Date());
            }
            return binaryResource;
        } else {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException4");
        }
    }

    @RolesAllowed({"users","admin"})
    @Override
    public List<ConfigurationItem> getConfigurationItems(String pWorkspaceId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        Locale locale = Locale.getDefault();
        if(!userManager.isCallerInRole("admin")){
            User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
            locale = new Locale(user.getLanguage());
        }

        return new ConfigurationItemDAO(locale, em).findAllConfigurationItems(pWorkspaceId);
    }

    /*
    * give pUsageLinks null for no modification, give an empty list for removing them
    * give pAttributes null for no modification, give an empty list for removing them
    * */
    @RolesAllowed("users")
    @Override
    public PartRevision updatePartIteration(PartIterationKey pKey, String pIterationNote, Source source, List<PartUsageLink> pUsageLinks, List<InstanceAttribute> pAttributes, DocumentIterationKey[] pLinkKeys) throws UserNotFoundException, WorkspaceNotFoundException, AccessRightException, NotAllowedException, PartRevisionNotFoundException, PartMasterNotFoundException {

        User user = userManager.checkWorkspaceWriteAccess(pKey.getWorkspaceId());
        PartRevisionDAO partRDAO = new PartRevisionDAO(new Locale(user.getLanguage()), em);
        PartRevision partRev = partRDAO.loadPartR(pKey.getPartRevision());

        //check access rights on partRevision
        Workspace wks = new WorkspaceDAO(new Locale(user.getLanguage()), em).loadWorkspace(pKey.getWorkspaceId());
        boolean isAdmin = wks.getAdmin().getLogin().equals(user.getLogin());
        if (!isAdmin && partRev.getACL() != null && !partRev.getACL().hasWriteAccess(user)) {
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }

        PartMasterDAO partMDAO = new PartMasterDAO(new Locale(user.getLanguage()), em);
        DocumentLinkDAO linkDAO = new DocumentLinkDAO(new Locale(user.getLanguage()), em);
        PartIteration partIte = partRev.getLastIteration();

        if (partRev.isCheckedOut() && partRev.getCheckOutUser().equals(user) && partIte.getKey().equals(pKey)) {
            if (pLinkKeys != null) {
                Set<DocumentIterationKey> linkKeys = new HashSet<>(Arrays.asList(pLinkKeys));
                Set<DocumentIterationKey> currentLinkKeys = new HashSet<>();

                Set<DocumentLink> currentLinks = new HashSet<>(partIte.getLinkedDocuments());

                for (DocumentLink link : currentLinks) {
                    DocumentIterationKey linkKey = link.getTargetDocumentKey();
                    if (!linkKeys.contains(linkKey)) {
                        partIte.getLinkedDocuments().remove(link);
                    } else {
                        currentLinkKeys.add(linkKey);
                    }
                }

                for (DocumentIterationKey link : linkKeys) {
                    if (!currentLinkKeys.contains(link)) {
                        DocumentLink newLink = new DocumentLink(em.getReference(DocumentIteration.class, link));
                        linkDAO.createLink(newLink);
                        partIte.getLinkedDocuments().add(newLink);
                    }
                }
            }
            if (pUsageLinks != null) {
                List<PartUsageLink> usageLinks = new LinkedList<>();
                for (PartUsageLink usageLink : pUsageLinks) {
                    PartUsageLink ul = new PartUsageLink();
                    ul.setAmount(usageLink.getAmount());
                    ul.setCadInstances(usageLink.getCadInstances());
                    ul.setComment(usageLink.getComment());
                    ul.setReferenceDescription(usageLink.getReferenceDescription());
                    ul.setUnit(usageLink.getUnit());
                    PartMaster pm = usageLink.getComponent();
                    PartMaster component = partMDAO.loadPartM(new PartMasterKey(pm.getWorkspaceId(), pm.getNumber()));
                    ul.setComponent(component);
                    List<PartSubstituteLink> substitutes = new LinkedList<>();
                    for (PartSubstituteLink substitute : usageLink.getSubstitutes()) {
                        PartSubstituteLink sub = new PartSubstituteLink();
                        sub.setCadInstances(substitute.getCadInstances());
                        sub.setComment(substitute.getComment());
                        sub.setReferenceDescription(substitute.getReferenceDescription());
                        PartMaster pmSub = substitute.getSubstitute();
                        sub.setSubstitute(partMDAO.loadPartM(new PartMasterKey(pmSub.getWorkspaceId(), pmSub.getNumber())));
                        substitutes.add(sub);
                    }
                    ul.setSubstitutes(substitutes);
                    usageLinks.add(ul);
                }

                partIte.setComponents(usageLinks);
            }
            if (pAttributes != null) {
                // set doc for all attributes
                Map<String, InstanceAttribute> attrs = new HashMap<>();
                for (InstanceAttribute attr : pAttributes) {
                    attrs.put(attr.getName(), attr);
                }

                Set<InstanceAttribute> currentAttrs = new HashSet<>(partIte.getInstanceAttributes().values());
                for (InstanceAttribute attr : currentAttrs) {
                    if (!attrs.containsKey(attr.getName())) {
                        partIte.getInstanceAttributes().remove(attr.getName());
                    }
                }

                for (InstanceAttribute attr : attrs.values()) {
                    if(!partIte.getInstanceAttributes().containsKey(attr.getName())){
                        partIte.getInstanceAttributes().put(attr.getName(), attr);
                    }else if(partIte.getInstanceAttributes().get(attr.getName()).getClass() != attr.getClass()){
                        partIte.getInstanceAttributes().remove(attr.getName());
                        partIte.getInstanceAttributes().put(attr.getName(), attr);
                    }else{
                        partIte.getInstanceAttributes().get(attr.getName()).setValue(attr.getValue());
                    }
                }
            }

            partIte.setIterationNote(pIterationNote);

            partIte.setSource(source);

            return partRev;

        } else {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException25");
        }

    }

    @RolesAllowed({"users","guest-proxy"})
    @Override
    public PartRevision getPartRevision(PartRevisionKey pPartRPK) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException, AccessRightException {

        if(ctx.isCallerInRole("guest-proxy")){
            PartRevision partRevision = new PartRevisionDAO(em).loadPartR(pPartRPK);
            if(partRevision.isCheckedOut()){
                em.detach(partRevision);
                partRevision.removeLastIteration();
            }
            return partRevision;
        }

        User user = checkPartRevisionReadAccess(pPartRPK);

        PartRevision partR = new PartRevisionDAO(new Locale(user.getLanguage()), em).loadPartR(pPartRPK);

        if ((partR.isCheckedOut()) && (!partR.getCheckOutUser().equals(user))) {
            em.detach(partR);
            partR.removeLastIteration();
        }
        return partR;
    }

    @RolesAllowed("users")
    @Override
    public void updatePartRevisionACL(String workspaceId, PartRevisionKey revisionKey, Map<String, String> pACLUserEntries, Map<String, String> pACLUserGroupEntries) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException, AccessRightException, DocumentRevisionNotFoundException {

        User user = userManager.checkWorkspaceReadAccess(workspaceId);
        PartRevisionDAO partRevisionDAO = new PartRevisionDAO(new Locale(user.getLanguage()),em);
        PartRevision partRevision = partRevisionDAO.loadPartR(revisionKey);
        Workspace wks = new WorkspaceDAO(em).loadWorkspace(workspaceId);

        if (partRevision.getAuthor().getLogin().equals(user.getLogin()) || wks.getAdmin().getLogin().equals(user.getLogin())) {

            if (partRevision.getACL() == null) {

                ACL acl = new ACL();

                if (pACLUserEntries != null) {
                    for (Map.Entry<String, String> entry : pACLUserEntries.entrySet()) {
                        acl.addEntry(em.getReference(User.class,new UserKey(workspaceId,entry.getKey())),ACL.Permission.valueOf(entry.getValue()));
                    }
                }

                if (pACLUserGroupEntries != null) {
                    for (Map.Entry<String, String> entry : pACLUserGroupEntries.entrySet()) {
                        acl.addEntry(em.getReference(UserGroup.class,new UserGroupKey(workspaceId,entry.getKey())),ACL.Permission.valueOf(entry.getValue()));
                    }
                }

                new ACLDAO(em).createACL(acl);
                partRevision.setACL(acl);

            }else{
                if (pACLUserEntries != null) {
                    for (ACLUserEntry entry : partRevision.getACL().getUserEntries().values()) {
                        ACL.Permission newPermission = ACL.Permission.valueOf(pACLUserEntries.get(entry.getPrincipalLogin()));
                        if(newPermission != null){
                            entry.setPermission(newPermission);
                        }
                    }
                }

                if (pACLUserGroupEntries != null) {
                    for (ACLUserGroupEntry entry : partRevision.getACL().getGroupEntries().values()) {
                        ACL.Permission newPermission = ACL.Permission.valueOf(pACLUserGroupEntries.get(entry.getPrincipalId()));
                        if(newPermission != null){
                            entry.setPermission(newPermission);
                        }
                    }
                }
            }

        }else {
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }


    }

    @RolesAllowed("users")
    @Override
    public void removeACLFromPartRevision(PartRevisionKey revisionKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException, AccessRightException {

        User user = userManager.checkWorkspaceReadAccess(revisionKey.getPartMaster().getWorkspace());
        PartRevisionDAO partRevisionDAO = new PartRevisionDAO(new Locale(user.getLanguage()),em);
        PartRevision partRevision = partRevisionDAO.loadPartR(revisionKey);
        Workspace wks = new WorkspaceDAO(em).loadWorkspace(revisionKey.getPartMaster().getWorkspace());

        if (partRevision.getAuthor().getLogin().equals(user.getLogin()) || wks.getAdmin().getLogin().equals(user.getLogin())) {
            ACL acl = partRevision.getACL();
            if (acl != null) {
                new ACLDAO(em).removeACLEntries(acl);
                partRevision.setACL(null);
            }
        }else{
            throw new AccessRightException(new Locale(user.getLanguage()), user);
        }

    }

    @RolesAllowed("users")
    @Override
    public void setRadiusForPartIteration(PartIterationKey pPartIPK, Float radius) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartIterationNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(pPartIPK.getWorkspaceId());
        PartIteration partI = new PartIterationDAO(new Locale(user.getLanguage()), em).loadPartI(pPartIPK);
        Map<String, InstanceAttribute> instanceAttributes = partI.getInstanceAttributes();
        InstanceNumberAttribute instanceNumberAttribute = new InstanceNumberAttribute();
        instanceNumberAttribute.setName("radius");
        instanceNumberAttribute.setNumberValue(radius);
        instanceAttributes.put("radius",instanceNumberAttribute);
    }

    @RolesAllowed("users")
    @Override
    public List<PartRevision> searchPartRevisions(PartSearchQuery pQuery) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, ESServerException {
        User user = userManager.checkWorkspaceReadAccess(pQuery.getWorkspaceId());
        List<PartRevision> fetchedPartRs = esIndexer.search(pQuery);
        // Get Search Results

        Workspace wks = new WorkspaceDAO(new Locale(user.getLanguage()), em).loadWorkspace(pQuery.getWorkspaceId());
        boolean isAdmin = wks.getAdmin().getLogin().equals(user.getLogin());

        ListIterator<PartRevision> ite = fetchedPartRs.listIterator();
        while (ite.hasNext()) {
            PartRevision partR = ite.next();

            if ((partR.isCheckedOut()) && (!partR.getCheckOutUser().equals(user))) {
            // Remove CheckedOut PartRevision From Results
                partR = partR.clone();
                partR.removeLastIteration();
                ite.set(partR);
            }

            //Check access rights
            if (!isAdmin && partR.getACL() != null && !partR.getACL().hasReadAccess(user)) {
            // Check Rigth Acces
                ite.remove();
            }
        }
        return new ArrayList<>(fetchedPartRs);
    }

    @RolesAllowed("users")
    @Override
    public PartMaster findPartMasterByCADFileName(String workspaceId, String cadFileName) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        userManager.checkWorkspaceReadAccess(workspaceId);
        BinaryResource br  = new BinaryResourceDAO(em).findNativeCadBinaryResourceInWorkspace(workspaceId,cadFileName);
        if(br == null){
            return null;
        }
        String partNumber = br.getOwnerId();
        PartMasterKey partMasterKey = new PartMasterKey(workspaceId,partNumber);
        try {
            return new PartMasterDAO(em).loadPartM(partMasterKey);
        } catch (PartMasterNotFoundException e) {
            return null;
        }

    }

    @RolesAllowed("users")
    @Override
    public PartRevision[] getPartRevisionsWithReference(String pWorkspaceId, String reference, int maxResults) throws WorkspaceNotFoundException, UserNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
        List<PartRevision> partRs = new PartRevisionDAO(new Locale(user.getLanguage()), em).findPartsRevisionsWithReferenceLike(pWorkspaceId, reference, maxResults);
        return partRs.toArray(new PartRevision[partRs.size()]);
    }

    @RolesAllowed("users")
    @Override
    public PartRevision releasePartRevision(PartRevisionKey pRevisionKey) throws UserNotFoundException, WorkspaceNotFoundException, UserNotActiveException, PartRevisionNotFoundException, AccessRightException, NotAllowedException {
        User user = checkPartRevisionWriteAccess(pRevisionKey);                                                         // Check if the user can write the part
        PartRevisionDAO partRevisionDAO = new PartRevisionDAO(new Locale(user.getLanguage()),em);

        PartRevision partRevision = partRevisionDAO.loadPartR(pRevisionKey);

        if(partRevision.isCheckedOut()){
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException40");
        }

        if (partRevision.getNumberOfIterations() == 0) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException41");
        }

        partRevision.release();
        return partRevision;
    }

    @RolesAllowed("users")
    @Override
    public List<ProductBaseline> getAllBaselines(String workspaceId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        userManager.checkWorkspaceReadAccess(workspaceId);
        return new ProductBaselineDAO(em).findBaselines(workspaceId);
    }

    @RolesAllowed("users")
    @Override
    public List<ProductInstanceMaster> getProductInstanceMasters(String workspaceId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        userManager.checkWorkspaceReadAccess(workspaceId);
        return new ProductInstanceMasterDAO(em).findProductInstanceMasters(workspaceId);
    }

    @RolesAllowed("users")
    @Override
    public List<ProductInstanceMaster> getProductInstanceMasters(ConfigurationItemKey configurationItemKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        userManager.checkWorkspaceReadAccess(configurationItemKey.getWorkspace());
        return new ProductInstanceMasterDAO(em).findProductInstanceMasters(configurationItemKey.getId(), configurationItemKey.getWorkspace());
    }

    @RolesAllowed("users")
    @Override
    public ProductInstanceMaster getProductInstanceMaster(ProductInstanceMasterKey productInstanceMasterKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, ProductInstanceMasterNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(productInstanceMasterKey.getInstanceOf().getWorkspace());
        Locale userLocal = new Locale(user.getLanguage());
        return new ProductInstanceMasterDAO(userLocal,em).loadProductInstanceMaster(productInstanceMasterKey);
    }

    @RolesAllowed("users")
    @Override
    public List<ProductInstanceIteration> getProductInstanceIterations(ProductInstanceMasterKey productInstanceMasterKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        userManager.checkWorkspaceReadAccess(productInstanceMasterKey.getInstanceOf().getWorkspace());
        return new ProductInstanceIterationDAO(em).findProductInstanceIterationsByMaster(productInstanceMasterKey);
    }

    @RolesAllowed("users")
    @Override
    public ProductInstanceIteration getProductInstanceIteration(ProductInstanceIterationKey productInstanceIterationKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, ProductInstanceIterationNotFoundException, ProductInstanceMasterNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(productInstanceIterationKey.getProductInstanceMaster().getInstanceOf().getWorkspace());
        Locale userLocal = new Locale(user.getLanguage());
        return new ProductInstanceIterationDAO(userLocal,em).loadProductInstanceIteration(productInstanceIterationKey);
    }

    @RolesAllowed("users")
    @Override
    public List<BaselinedPart> getProductInstanceIterationBaselinedPart(ProductInstanceIterationKey productInstanceIterationKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, ProductInstanceIterationNotFoundException, ProductInstanceMasterNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(productInstanceIterationKey.getProductInstanceMaster().getInstanceOf().getWorkspace());
        Locale userLocal = new Locale(user.getLanguage());
        ProductInstanceIteration productInstanceIteration = new ProductInstanceIterationDAO(userLocal,em).loadProductInstanceIteration(productInstanceIterationKey);
        return new ArrayList<>(productInstanceIteration.getPartCollection().getBaselinedParts().values());
    }

    @Override
    public List<BaselinedPart> getProductInstanceIterationPartWithReference(ProductInstanceIterationKey productInstanceIterationKey, String q, int maxResults) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, ProductInstanceIterationNotFoundException, ProductInstanceMasterNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(productInstanceIterationKey.getProductInstanceMaster().getInstanceOf().getWorkspace());
        ProductInstanceIterationDAO productInstanceIterationDAO = new ProductInstanceIterationDAO(new Locale(user.getLanguage()),em);
        ProductInstanceIteration productInstanceIteration = productInstanceIterationDAO.loadProductInstanceIteration(productInstanceIterationKey);
        return productInstanceIterationDAO.findBaselinedPartWithReferenceLike(productInstanceIteration.getPartCollection().getId(), q, maxResults);
    }

    @RolesAllowed("users")
    @Override
    public ProductInstanceMaster createProductInstance(ConfigurationItemKey configurationItemKey, String serialNumber, int baselineId) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, ConfigurationItemNotFoundException, BaselineNotFoundException, CreationException, ProductInstanceAlreadyExistsException {
        User user = userManager.checkWorkspaceWriteAccess(configurationItemKey.getWorkspace());
        Locale userLocal = new Locale(user.getLanguage());
        ProductInstanceMasterDAO productInstanceMasterDAO = new ProductInstanceMasterDAO(userLocal,em);

        try{                                                                                                            // Check if ths product instance already exist
            ProductInstanceMaster productInstanceMaster= productInstanceMasterDAO.loadProductInstanceMaster(new ProductInstanceMasterKey(serialNumber,configurationItemKey.getWorkspace(),configurationItemKey.getId()));
            throw new ProductInstanceAlreadyExistsException(userLocal, productInstanceMaster);
        }catch (ProductInstanceMasterNotFoundException e){
            LOGGER.log(Level.FINEST,null,e);
        }

        ConfigurationItem configurationItem = new ConfigurationItemDAO(em).loadConfigurationItem(configurationItemKey);
        ProductInstanceMaster productInstanceMaster = new ProductInstanceMaster(configurationItem,serialNumber);

        ProductInstanceIteration productInstanceIteration = productInstanceMaster.createNextIteration();
        productInstanceIteration.setIterationNote("Initial");
        PartCollection partCollection = new PartCollection();
        partCollection.setAuthor(user);
        partCollection.setCreationDate(new Date());

        ProductBaseline productBaseline = new ProductBaselineDAO(em).loadBaseline(baselineId);
        for(BaselinedPart baselinedPart : productBaseline.getBaselinedParts().values()){
            partCollection.addBaselinedPart(baselinedPart.getTargetPart());
        }
        productInstanceIteration.setPartCollection(partCollection);

        productInstanceMasterDAO.createProductInstanceMaster(productInstanceMaster);
        return productInstanceMaster;
    }

    @Override
    public ProductInstanceIteration updateProductInstance(ConfigurationItemKey configurationItemKey, String serialNumber, String iterationNote, List<PartIterationKey> partIterationKeys) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, ProductInstanceMasterNotFoundException, PartIterationNotFoundException {
        User user = userManager.checkWorkspaceWriteAccess(configurationItemKey.getWorkspace());
        Locale userLocal = new Locale(user.getLanguage());
        ProductInstanceMasterDAO productInstanceMasterDAO = new ProductInstanceMasterDAO(userLocal,em);
        ProductInstanceMaster productInstanceMaster= productInstanceMasterDAO.loadProductInstanceMaster(new ProductInstanceMasterKey(serialNumber,configurationItemKey.getWorkspace(),configurationItemKey.getId()));
        ProductInstanceIteration productInstanceIteration = productInstanceMaster.createNextIteration();
        productInstanceIteration.setIterationNote(iterationNote);
        PartCollection partCollection = new PartCollection();
        partCollection.setAuthor(user);
        partCollection.setCreationDate(new Date());
        for(PartIterationKey partIterationKey : partIterationKeys){
            partCollection.addBaselinedPart(new PartIterationDAO(userLocal,em).loadPartI(partIterationKey));
        }
        productInstanceIteration.setPartCollection(partCollection);
        return productInstanceIteration;
    }

    @RolesAllowed("users")
    @Override
    public void deleteProductInstance(String workspaceId, String configurationItemId, String serialNumber) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, UserNotActiveException, ProductInstanceMasterNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(workspaceId);
        Locale userLocal = new Locale(user.getLanguage());
        ProductInstanceMasterDAO productInstanceMasterDAO = new ProductInstanceMasterDAO(userLocal,em);
        ProductInstanceMaster prodInstM = productInstanceMasterDAO.loadProductInstanceMaster(new ProductInstanceMasterKey(serialNumber,workspaceId,configurationItemId));
        productInstanceMasterDAO.deleteProductInstanceMaster(prodInstM);
    }

    @RolesAllowed("users")
    @Override
    public List<ProductBaseline> findBaselinesWherePartRevisionHasIterations(PartRevisionKey partRevisionKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(partRevisionKey.getPartMaster().getWorkspace());
        PartRevision partRevision = new PartRevisionDAO(new Locale(user.getLanguage()),em).loadPartR(partRevisionKey);
        return new ProductBaselineDAO(em).findBaselineWherePartRevisionHasIterations(partRevision);
    }

    @RolesAllowed("users")
    @Override
    public List<PartUsageLink> getComponents(PartIterationKey pPartIPK) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartIterationNotFoundException, NotAllowedException {
        User user = userManager.checkWorkspaceReadAccess(pPartIPK.getWorkspaceId());
        PartIteration partI = new PartIterationDAO(new Locale(user.getLanguage()), em).loadPartI(pPartIPK);
        PartRevision partR = partI.getPartRevision();

        if ((partR.isCheckedOut()) && (!partR.getCheckOutUser().equals(user)) && partR.getLastIteration().equals(partI)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException34");
        }
        return partI.getComponents();
    }

    @RolesAllowed("users")
    @Override
    public boolean partMasterExists(PartMasterKey partMasterKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(partMasterKey.getWorkspace());
        try {
            new PartMasterDAO(new Locale(user.getLanguage()), em).loadPartM(partMasterKey);
            return true;
        } catch (PartMasterNotFoundException e) {
            LOGGER.log(Level.FINEST,null,e);
            return false;
        }
    }

    @Override
    public void deleteConfigurationItem(ConfigurationItemKey configurationItemKey) throws UserNotFoundException, WorkspaceNotFoundException, AccessRightException, NotAllowedException, UserNotActiveException, ConfigurationItemNotFoundException, LayerNotFoundException, EntityConstraintException {
        User user = userManager.checkWorkspaceReadAccess(configurationItemKey.getWorkspace());
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        List<ProductBaseline> productBaselines = productBaselineDAO.findBaselines(configurationItemKey.getId(), configurationItemKey.getWorkspace());
        if(!productBaselines.isEmpty() ){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException4");
        }
        new ConfigurationItemDAO(new Locale(user.getLanguage()),em).removeConfigurationItem(configurationItemKey);
    }

    @Override
    public void deleteLayer(String workspaceId, int layerId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, LayerNotFoundException, AccessRightException {
        Layer layer = new LayerDAO(em).loadLayer(layerId);
        User user = userManager.checkWorkspaceWriteAccess(layer.getConfigurationItem().getWorkspaceId());
        new LayerDAO(new Locale(user.getLanguage()),em).deleteLayer(layer);
    }

    @Override
    public void removeCADFileFromPartIteration(PartIterationKey partIKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartIterationNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(partIKey.getWorkspaceId());

        PartIteration partIteration = new PartIterationDAO(new Locale(user.getLanguage()),em).loadPartI(partIKey);
        BinaryResource br = partIteration.getNativeCADFile();
        if(br != null){
            try {
                dataManager.deleteData(br);
            } catch (StorageException e) {
                LOGGER.log(Level.INFO, null, e);
            }
            partIteration.setNativeCADFile(null);
        }

        List<Geometry> geometries = new ArrayList<>(partIteration.getGeometries());
        for(Geometry geometry : geometries){
            try {
                dataManager.deleteData(geometry);
            } catch (StorageException e) {
                LOGGER.log(Level.INFO, null, e);
            }
            partIteration.removeGeometry(geometry);
        }

        Set<BinaryResource> attachedFiles = new HashSet<>(partIteration.getAttachedFiles());
        for(BinaryResource attachedFile : attachedFiles){
            try {
                dataManager.deleteData(attachedFile);
            } catch (StorageException e) {
                LOGGER.log(Level.INFO, null, e);
            }
            partIteration.removeFile(attachedFile);
        }
    }

    @RolesAllowed("users")
    @Override
    public PartMaster getPartMaster(PartMasterKey pPartMPK) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartMasterNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(pPartMPK.getWorkspace());
        PartMaster partM = new PartMasterDAO(new Locale(user.getLanguage()), em).loadPartM(pPartMPK);

        for (PartRevision partR : partM.getPartRevisions()) {
            if ((partR.isCheckedOut()) && (!partR.getCheckOutUser().equals(user))) {
                em.detach(partR);
                partR.removeLastIteration();
            }
        }
        return partM;
    }

    @RolesAllowed("users")
    @Override
    public List<Layer> getLayers(ConfigurationItemKey pKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(pKey.getWorkspace());
        return new LayerDAO(new Locale(user.getLanguage()), em).findAllLayers(pKey);
    }

    @RolesAllowed("users")
    @Override
    public Layer getLayer(int pId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, LayerNotFoundException {
        Layer layer = new LayerDAO(em).loadLayer(pId);
        userManager.checkWorkspaceReadAccess(layer.getConfigurationItem().getWorkspaceId());
        return layer;
    }

    @RolesAllowed("users")
    @Override
    public Layer createLayer(ConfigurationItemKey pKey, String pName, String color) throws UserNotFoundException, WorkspaceNotFoundException, AccessRightException, ConfigurationItemNotFoundException {
        User user = userManager.checkWorkspaceWriteAccess(pKey.getWorkspace());
        ConfigurationItem ci = new ConfigurationItemDAO(new Locale(user.getLanguage()), em).loadConfigurationItem(pKey);
        Layer layer = new Layer(pName, user, ci, color);
        Date now = new Date();
        layer.setCreationDate(now);

        new LayerDAO(new Locale(user.getLanguage()), em).createLayer(layer);
        return layer;
    }

    @RolesAllowed("users")
    @Override
    public Layer updateLayer(ConfigurationItemKey pKey, int pId, String pName, String color) throws UserNotFoundException, WorkspaceNotFoundException, AccessRightException, ConfigurationItemNotFoundException, LayerNotFoundException, UserNotActiveException {
        Layer layer = getLayer(pId);
        userManager.checkWorkspaceWriteAccess(layer.getConfigurationItem().getWorkspaceId());
        layer.setName(pName);
        layer.setColor(color);
        return layer;
    }

    @RolesAllowed("users")
    @Override
    public Marker createMarker(int pLayerId, String pTitle, String pDescription, double pX, double pY, double pZ) throws LayerNotFoundException, UserNotFoundException, WorkspaceNotFoundException, AccessRightException {
        Layer layer = new LayerDAO(em).loadLayer(pLayerId);
        User user = userManager.checkWorkspaceWriteAccess(layer.getConfigurationItem().getWorkspaceId());
        Marker marker = new Marker(pTitle, user, pDescription, pX, pY, pZ);
        Date now = new Date();
        marker.setCreationDate(now);

        new MarkerDAO(new Locale(user.getLanguage()), em).createMarker(marker);
        layer.addMarker(marker);
        return marker;
    }

    @RolesAllowed("users")
    @Override
    public void deleteMarker(int pLayerId, int pMarkerId) throws WorkspaceNotFoundException, UserNotActiveException, LayerNotFoundException, UserNotFoundException, AccessRightException, MarkerNotFoundException {
        Layer layer = new LayerDAO(em).loadLayer(pLayerId);
        User user = userManager.checkWorkspaceWriteAccess(layer.getConfigurationItem().getWorkspaceId());
        Locale locale = new Locale(user.getLanguage());
        Marker marker = new MarkerDAO(locale, em).loadMarker(pMarkerId);

        if (layer.getMarkers().contains(marker)) {
            layer.removeMarker(marker);
            em.flush();
            new MarkerDAO(locale, em).removeMarker(pMarkerId);
        } else {
            throw new MarkerNotFoundException(locale, pMarkerId);
        }

    }

    @RolesAllowed("users")
    @Override
    public PartMasterTemplate[] getPartMasterTemplates(String pWorkspaceId) throws WorkspaceNotFoundException, UserNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
        return new PartMasterTemplateDAO(new Locale(user.getLanguage()), em).findAllPartMTemplates(pWorkspaceId);
    }

    @RolesAllowed("users")
    @Override
    public PartMasterTemplate getPartMasterTemplate(PartMasterTemplateKey pKey) throws WorkspaceNotFoundException, PartMasterTemplateNotFoundException, UserNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pKey.getWorkspaceId());
        return new PartMasterTemplateDAO(new Locale(user.getLanguage()), em).loadPartMTemplate(pKey);
    }


    @RolesAllowed("users")
    @Override
    public PartMasterTemplate createPartMasterTemplate(String pWorkspaceId, String pId, String pPartType, String pMask, InstanceAttributeTemplate[] pAttributeTemplates, boolean idGenerated, boolean attributesLocked) throws WorkspaceNotFoundException, AccessRightException, PartMasterTemplateAlreadyExistsException, UserNotFoundException, NotAllowedException, CreationException {
        User user = userManager.checkWorkspaceWriteAccess(pWorkspaceId);
        if (!NamingConvention.correct(pId)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException9");
        }
        PartMasterTemplate template = new PartMasterTemplate(user.getWorkspace(), pId, user, pPartType, pMask, attributesLocked);
        Date now = new Date();
        template.setCreationDate(now);
        template.setIdGenerated(idGenerated);

        Set<InstanceAttributeTemplate> attrs = new HashSet<>();
        Collections.addAll(attrs, pAttributeTemplates);
        template.setAttributeTemplates(attrs);

        new PartMasterTemplateDAO(new Locale(user.getLanguage()), em).createPartMTemplate(template);
        return template;
    }

    @RolesAllowed("users")
    @Override
    public PartMasterTemplate updatePartMasterTemplate(PartMasterTemplateKey pKey, String pPartType, String pMask, InstanceAttributeTemplate[] pAttributeTemplates, boolean idGenerated, boolean attributesLocked) throws WorkspaceNotFoundException, AccessRightException, PartMasterTemplateNotFoundException, UserNotFoundException {
        User user = userManager.checkWorkspaceWriteAccess(pKey.getWorkspaceId());

        PartMasterTemplateDAO templateDAO = new PartMasterTemplateDAO(new Locale(user.getLanguage()), em);
        PartMasterTemplate template = templateDAO.loadPartMTemplate(pKey);
        Date now = new Date();
        template.setCreationDate(now);
        template.setAuthor(user);
        template.setPartType(pPartType);
        template.setMask(pMask);
        template.setIdGenerated(idGenerated);
        template.setAttributesLocked(attributesLocked);

        Set<InstanceAttributeTemplate> attrs = new HashSet<>();
        Collections.addAll(attrs, pAttributeTemplates);

        Set<InstanceAttributeTemplate> attrsToRemove = new HashSet<>(template.getAttributeTemplates());
        attrsToRemove.removeAll(attrs);

        InstanceAttributeTemplateDAO attrDAO = new InstanceAttributeTemplateDAO(em);
        for (InstanceAttributeTemplate attrToRemove : attrsToRemove) {
            attrDAO.removeAttribute(attrToRemove);
        }

        template.setAttributeTemplates(attrs);
        return template;
    }

    @RolesAllowed("users")
    @Override
    public void deletePartMasterTemplate(PartMasterTemplateKey pKey) throws WorkspaceNotFoundException, AccessRightException, PartMasterTemplateNotFoundException, UserNotFoundException {
        User user = userManager.checkWorkspaceWriteAccess(pKey.getWorkspaceId());
        PartMasterTemplateDAO templateDAO = new PartMasterTemplateDAO(new Locale(user.getLanguage()), em);
        PartMasterTemplate template = templateDAO.removePartMTemplate(pKey);
        BinaryResource file = template.getAttachedFile();
        if(file != null){
            try {
                dataManager.deleteData(file);
            } catch (StorageException e) {
                LOGGER.log(Level.INFO, null, e);
            }
        }
    }

    @RolesAllowed("users")
    @Override
    public BinaryResource saveFileInTemplate(PartMasterTemplateKey pPartMTemplateKey, String pName, long pSize) throws WorkspaceNotFoundException, NotAllowedException, PartMasterTemplateNotFoundException, FileAlreadyExistsException, UserNotFoundException, UserNotActiveException, CreationException {
        userManager.checkWorkspaceReadAccess(pPartMTemplateKey.getWorkspaceId());
        //TODO checkWorkspaceWriteAccess ?
        if (!NamingConvention.correctNameFile(pName)) {
            throw new NotAllowedException(Locale.getDefault(), "NotAllowedException9");
        }

        PartMasterTemplateDAO templateDAO = new PartMasterTemplateDAO(em);
        PartMasterTemplate template = templateDAO.loadPartMTemplate(pPartMTemplateKey);
        BinaryResource binaryResource = null;
        String fullName = template.getWorkspaceId() + "/part-templates/" + template.getId() + "/" + pName;

        BinaryResource bin = template.getAttachedFile();
        if(bin != null && bin.getFullName().equals(fullName)) {
            binaryResource = bin;
        }

        if (binaryResource == null) {
            binaryResource = new BinaryResource(fullName, pSize, new Date());
            new BinaryResourceDAO(em).createBinaryResource(binaryResource);
            template.setAttachedFile(binaryResource);
        } else {
            binaryResource.setContentLength(pSize);
            binaryResource.setLastModified(new Date());
        }
        return binaryResource;
    }

    @RolesAllowed("users")
    @Override
    public PartMasterTemplate removeFileFromTemplate(String pFullName) throws WorkspaceNotFoundException, PartMasterTemplateNotFoundException, AccessRightException, FileNotFoundException, UserNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(BinaryResource.parseWorkspaceId(pFullName));
        //TODO checkWorkspaceWriteAccess ?
        BinaryResourceDAO binDAO = new BinaryResourceDAO(new Locale(user.getLanguage()), em);
        BinaryResource file = binDAO.loadBinaryResource(pFullName);

        PartMasterTemplate template = binDAO.getPartTemplateOwner(file);
        try {
            dataManager.deleteData(file);
        } catch (StorageException e) {
            LOGGER.log(Level.INFO, null, e);
        }
        template.setAttachedFile(null);
        binDAO.removeBinaryResource(file);
        return template;
    }

    @RolesAllowed("users")
    @Override
    public List<PartMaster> getPartMasters(String pWorkspaceId, int start, int pMaxResults) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
        return new PartMasterDAO(new Locale(user.getLanguage()), em).getPartMasters(pWorkspaceId, start, pMaxResults);
    }

    @RolesAllowed("users")
    @Override
    public List<PartRevision> getPartRevisions(String pWorkspaceId, int start, int pMaxResults) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
        List<PartRevision> partRevisions = new PartRevisionDAO(new Locale(user.getLanguage()), em).getPartRevisions(pWorkspaceId, start, pMaxResults);
        List<PartRevision> filtredPartRevisions = new ArrayList<>();
        for(PartRevision partRevision : partRevisions){
            try{
                checkPartRevisionReadAccess(partRevision.getKey());
                filtredPartRevisions.add(partRevision);
            } catch (AccessRightException | PartRevisionNotFoundException e) {
                LOGGER.log(Level.FINER,null,e);
            }
        }
        return filtredPartRevisions;
    }

    @RolesAllowed({"users"})
    @Override
    public int getPartsInWorkspaceCount(String pWorkspaceId) throws WorkspaceNotFoundException, UserNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
        return new PartRevisionDAO(new Locale(user.getLanguage()), em).getPartRevisionCountFiltered(user, pWorkspaceId);
    }

    @RolesAllowed({"users","admin"})
    @Override
    public int getTotalNumberOfParts(String pWorkspaceId) throws AccessRightException, WorkspaceNotFoundException, AccountNotFoundException, UserNotFoundException, UserNotActiveException {
        Locale locale = Locale.getDefault();
        if(!userManager.isCallerInRole("admin")){
            User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
            locale = new Locale(user.getLanguage());
        }
        //Todo count only part you can see
        return new PartRevisionDAO(locale, em).getTotalNumberOfParts(pWorkspaceId);
    }

    @RolesAllowed("users")
    @Override
    public void deletePartMaster(PartMasterKey partMasterKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartMasterNotFoundException, EntityConstraintException, ESServerException {

        User user = userManager.checkWorkspaceReadAccess(partMasterKey.getWorkspace());

        PartMasterDAO partMasterDAO = new PartMasterDAO(new Locale(user.getLanguage()), em);
        PartUsageLinkDAO partUsageLinkDAO = new PartUsageLinkDAO(new Locale(user.getLanguage()), em);
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ConfigurationItemDAO configurationItemDAO = new ConfigurationItemDAO(new Locale(user.getLanguage()),em);
        PartMaster partMaster = partMasterDAO.loadPartM(partMasterKey);

        // check if part is linked to a product
        if(configurationItemDAO.isPartMasterLinkedToConfigurationItem(partMaster)){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException1");
        }

        // check if this part is in a partUsage
        if(partUsageLinkDAO.hasPartUsages(partMasterKey.getWorkspace(),partMasterKey.getNumber())){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException2");
        }

        // check if part is baselined
        if(productBaselineDAO.existBaselinedPart(partMasterKey.getWorkspace(),partMasterKey.getNumber())){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException5");
        }

        // delete CAD files attached with this partMaster
        for (PartRevision partRevision : partMaster.getPartRevisions()) {
            for (PartIteration partIteration : partRevision.getPartIterations()) {
                try {
                    removeCADFileFromPartIteration(partIteration.getKey());
                } catch (PartIterationNotFoundException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }
        }

        // delete ElasticSearch Index for this revision iteration
        for (PartRevision partRevision : partMaster.getPartRevisions()) {
            for (PartIteration partIteration : partRevision.getPartIterations()) {
                esIndexer.delete(partIteration);
                // Remove ElasticSearch Index for this PartIteration
            }
        }

        // ok to delete
        partMasterDAO.removePartM(partMaster);
    }


    @RolesAllowed("users")
    @Override
    public void deletePartRevision(PartRevisionKey partRevisionKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException, EntityConstraintException, ESServerException {

        User user = userManager.checkWorkspaceReadAccess(partRevisionKey.getPartMaster().getWorkspace());

        PartMasterDAO partMasterDAO = new PartMasterDAO(new Locale(user.getLanguage()), em);
        PartRevisionDAO partRevisionDAO = new PartRevisionDAO(new Locale(user.getLanguage()), em);
        PartUsageLinkDAO partUsageLinkDAO = new PartUsageLinkDAO(new Locale(user.getLanguage()), em);
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(new Locale(user.getLanguage()),em);

        ConfigurationItemDAO configurationItemDAO = new ConfigurationItemDAO(new Locale(user.getLanguage()),em);

        PartRevision partR = partRevisionDAO.loadPartR(partRevisionKey);
        PartMaster partMaster = partR.getPartMaster();
        boolean isLastRevision = partMaster.getPartRevisions().size() == 1;

        //TODO all the 3 removal restrictions may be performed
        //more precisely on PartRevision rather on PartMaster
        // check if part is linked to a product
        if(configurationItemDAO.isPartMasterLinkedToConfigurationItem(partMaster)){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException1");
        }
        // check if this part is in a partUsage
        if(partUsageLinkDAO.hasPartUsages(partMaster.getWorkspaceId(),partMaster.getNumber())){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException2");
        }

        // check if part is baselined
        if(productBaselineDAO.existBaselinedPart(partMaster.getWorkspaceId(),partMaster.getNumber())){
            throw new EntityConstraintException(new Locale(user.getLanguage()),"EntityConstraintException5");
        }

        // delete ElasticSearch Index for this revision iteration
        for (PartIteration partIteration : partR.getPartIterations()) {
            esIndexer.delete(partIteration);
            // Remove ElasticSearch Index for this PartIteration
        }

        // delete CAD files attached with this partMaster
        for (PartIteration partIteration : partR.getPartIterations()) {
            try {
                removeCADFileFromPartIteration(partIteration.getKey());
            } catch (PartIterationNotFoundException e) {
                LOGGER.log(Level.INFO, null, e);
            }
        }

        if(isLastRevision){
            partMasterDAO.removePartM(partMaster);
        }else{
            partMaster.removeRevision(partR);
            partRevisionDAO.removeRevision(partR);
        }

    }

    @RolesAllowed("users")
    @Override
    public int getNumberOfIteration(PartRevisionKey partRevisionKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException {
        User user = userManager.checkWorkspaceReadAccess(partRevisionKey.getPartMaster().getWorkspace());
        return new PartRevisionDAO(new Locale(user.getLanguage()), em).loadPartR(partRevisionKey).getLastIterationNumber();
    }

    @RolesAllowed("users")
    @Override
    public BaselineCreation createBaseline(ConfigurationItemKey configurationItemKey, String name, ProductBaseline.BaselineType pType, String description) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, ConfigurationItemNotFoundException, ConfigurationItemNotReleasedException, PartIterationNotFoundException, UserNotActiveException, NotAllowedException{

        User user = userManager.checkWorkspaceWriteAccess(configurationItemKey.getWorkspace());
        Locale locale = new Locale(user.getLanguage());
        ConfigurationItem configurationItem = new ConfigurationItemDAO(locale,em).loadConfigurationItem(configurationItemKey);

        ProductBaseline.BaselineType type = pType;
        if(type == null){
            type = ProductBaseline.BaselineType.LATEST;
        }
        ProductBaseline productBaseline = new ProductBaseline(configurationItem, name, type, description);
        Date now = new Date();
        productBaseline.getPartCollection().setCreationDate(now);
        productBaseline.getPartCollection().setAuthor(user);

        new ProductBaselineDAO(em).createBaseline(productBaseline);

        BaselineCreation baselineCreation = new BaselineCreation(productBaseline);

        PartRevision lastRevision;
        PartIteration baselinedIteration = null;

        switch(type){
            case RELEASED:
                lastRevision = configurationItem.getDesignItem().getLastReleasedRevision();
                if(lastRevision==null){
                    throw new ConfigurationItemNotReleasedException(locale, configurationItemKey.getId());
                }
                baselinedIteration = lastRevision.getLastIteration();
                break;
            // case LATEST:
            default:
                List<PartRevision> partRevisions = configurationItem.getDesignItem().getPartRevisions();
                boolean isPartFinded = false;

                lastRevision =configurationItem.getDesignItem().getLastRevision();
                if(lastRevision.isCheckedOut()){
                    baselineCreation.addConflit(lastRevision);
                }

                for(int j= partRevisions.size()-1; j>=0 && !isPartFinded;j--){
                    lastRevision = partRevisions.get(j);

                    for(int i= lastRevision.getLastIteration().getIteration(); i>0 && !isPartFinded; i--){
                        try{
                            checkPartIterationForBaseline(new PartIterationKey(lastRevision.getKey(),i));
                            baselinedIteration = lastRevision.getIteration(i);
                            isPartFinded=true;
                        }catch (AccessRightException e){
                            if(!baselineCreation.getConflit().contains(lastRevision)){
                                baselineCreation.addConflit(lastRevision);
                            }
                        }
                    }
                }
                if(baselinedIteration==null){
                    throw new NotAllowedException(locale, "NotAllowedException1");
                }
                break;
        }

        baselineCreation.addConflit(fillBaselineParts(productBaseline, baselinedIteration, type, locale));

        if(!baselineCreation.getConflit().isEmpty()){
            String message = ResourceBundle.getBundle("com.docdoku.core.i18n.LocalStrings", locale).getString("BaselineWarningException1");
            baselineCreation.setMessage(MessageFormat.format(message, productBaseline.getName()));
        }


        return baselineCreation;
    }

    @RolesAllowed("users")
    @Override
    public ProductBaseline duplicateBaseline(int baselineId, String name, ProductBaseline.BaselineType pType, String description) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, BaselineNotFoundException{
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ProductBaseline productBaseline = productBaselineDAO.loadBaseline(baselineId);
        ConfigurationItem configurationItem = productBaseline.getConfigurationItem();
        User user = userManager.checkWorkspaceWriteAccess(configurationItem.getWorkspaceId());

        ProductBaseline.BaselineType type = pType;
        if(pType == null){
            type = ProductBaseline.BaselineType.LATEST;
        }
        ProductBaseline duplicatedProductBaseline = new ProductBaseline(configurationItem,name, type, description);
        productBaselineDAO.createBaseline(duplicatedProductBaseline);
        Date now = new Date();
        productBaseline.getPartCollection().setCreationDate(now);
        productBaseline.getPartCollection().setAuthor(user);

        // copy partIterations
        Set<Map.Entry<BaselinedPartKey, BaselinedPart>> entries = productBaseline.getBaselinedParts().entrySet();
        for(Map.Entry<BaselinedPartKey,BaselinedPart> entry : entries){
            duplicatedProductBaseline.addBaselinedPart(entry.getValue().getTargetPart());
        }

        return duplicatedProductBaseline;

    }

    @RolesAllowed("users")
    @Override
    public List<ProductBaseline> getBaselines(ConfigurationItemKey configurationItemKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        userManager.checkWorkspaceReadAccess(configurationItemKey.getWorkspace());
        return new ProductBaselineDAO(em).findBaselines(configurationItemKey.getId(), configurationItemKey.getWorkspace());
    }

    @RolesAllowed("users")
    @Override
    public ProductBaseline getBaseline(int baselineId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, BaselineNotFoundException {
        ProductBaseline productBaseline = new ProductBaselineDAO(em).loadBaseline(baselineId);
        userManager.checkWorkspaceReadAccess(productBaseline.getConfigurationItem().getWorkspaceId());
        return productBaseline;
    }

    @RolesAllowed("users")
    @Override
    public ProductBaseline getBaselineById(int baselineId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ProductBaseline productBaseline = productBaselineDAO.findBaselineById(baselineId);
        Workspace workspace = productBaseline.getConfigurationItem().getWorkspace();
        userManager.checkWorkspaceReadAccess(workspace.getId());
        return productBaseline;
    }

    @RolesAllowed("users")
    @Override
    public void updateBaseline(ConfigurationItemKey configurationItemKey, int baselineId, String name, ProductBaseline.BaselineType type, String description, List<PartIterationKey> partIterationKeys) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartIterationNotFoundException, BaselineNotFoundException, ConfigurationItemNotReleasedException {
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ProductBaseline productBaseline = productBaselineDAO.loadBaseline(baselineId);
        User user = userManager.checkWorkspaceReadAccess(productBaseline.getConfigurationItem().getWorkspaceId());

        productBaseline.setDescription(description);
        productBaseline.setName(name);
        productBaseline.setType(type);
        productBaselineDAO.flushBaselinedParts(productBaseline);

        Locale locale = new Locale(user.getLanguage());
        PartIterationDAO partIterationDAO = new PartIterationDAO(locale,em);
        for(PartIterationKey partIterationKey : partIterationKeys){
            PartIteration partIteration = partIterationDAO.loadPartI(partIterationKey);
            if(type== ProductBaseline.BaselineType.LATEST || (type== ProductBaseline.BaselineType.RELEASED && partIteration.getPartRevision().isReleased())){
                productBaseline.addBaselinedPart(partIteration);
            }else{
                throw new ConfigurationItemNotReleasedException(locale,partIteration.getPartRevisionKey().toString());
            }
        }
    }

    @RolesAllowed("users")
    @Override
    public List<BaselinedPart> getBaselinedPartWithReference(int baselineId, String q, int maxResults) throws BaselineNotFoundException, UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException {
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ProductBaseline productBaseline = productBaselineDAO.loadBaseline(baselineId);
        User user = userManager.checkWorkspaceReadAccess(productBaseline.getConfigurationItem().getWorkspaceId());
        return new ProductBaselineDAO(new Locale(user.getLanguage()), em).findBaselinedPartWithReferenceLike(productBaseline.getPartCollection().getId(), q, maxResults);
    }

    @RolesAllowed("users")
    @Override
    public PartRevision createPartRevision(PartRevisionKey revisionKey, String pDescription, String pWorkflowModelId, ACLUserEntry[] pACLUserEntries, ACLUserGroupEntry[] pACLUserGroupEntries, Map<String, String> roleMappings) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, PartRevisionNotFoundException, NotAllowedException, FileAlreadyExistsException, CreationException, RoleNotFoundException, WorkflowModelNotFoundException, PartRevisionAlreadyExistsException {

        User user = userManager.checkWorkspaceWriteAccess(revisionKey.getPartMaster().getWorkspace());
        PartRevisionDAO partRevisionDAO = new PartRevisionDAO(new Locale(user.getLanguage()),em);

        PartRevision originalPartR = partRevisionDAO.loadPartR(revisionKey);
        PartMaster partM = originalPartR.getPartMaster();

        if(originalPartR.isCheckedOut()){
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException40");
        }

        if (originalPartR.getNumberOfIterations() == 0) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException41");
        }

        PartRevision partR = partM.createNextRevision(user);

        PartIteration lastPartI = originalPartR.getLastIteration();
        PartIteration firstPartI = partR.createNextIteration(user);


        if(lastPartI != null){

            BinaryResourceDAO binDAO = new BinaryResourceDAO(new Locale(user.getLanguage()), em);
            for (BinaryResource sourceFile : lastPartI.getAttachedFiles()) {
                String fileName = sourceFile.getName();
                long length = sourceFile.getContentLength();
                Date lastModified = sourceFile.getLastModified();
                String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/1/"+  fileName;
                BinaryResource targetFile = new BinaryResource(fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                firstPartI.addFile(targetFile);
                try {
                    dataManager.copyData(sourceFile, targetFile);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }

            // copy components
            List<PartUsageLink> components = new LinkedList<>();
            for (PartUsageLink usage : lastPartI.getComponents()) {
                PartUsageLink newUsage = usage.clone();
                components.add(newUsage);
            }
            firstPartI.setComponents(components);

            // copy geometries
            for (Geometry sourceFile : lastPartI.getGeometries()) {
                String fileName = sourceFile.getName();
                long length = sourceFile.getContentLength();
                int quality = sourceFile.getQuality();
                Date lastModified = sourceFile.getLastModified();
                String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/1/" + fileName;
                Geometry targetFile = new Geometry(quality, fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                firstPartI.addGeometry(targetFile);
                try {
                    dataManager.copyData(sourceFile, targetFile);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }

            BinaryResource nativeCADFile = lastPartI.getNativeCADFile();
            if (nativeCADFile != null) {
                String fileName = nativeCADFile.getName();
                long length = nativeCADFile.getContentLength();
                Date lastModified = nativeCADFile.getLastModified();
                String fullName = partR.getWorkspaceId() + "/parts/" + partR.getPartNumber() + "/" + partR.getVersion() + "/1/nativecad/" + fileName;
                BinaryResource targetFile = new BinaryResource(fullName, length, lastModified);
                binDAO.createBinaryResource(targetFile);
                firstPartI.setNativeCADFile(targetFile);
                try {
                    dataManager.copyData(nativeCADFile, targetFile);
                } catch (StorageException e) {
                    LOGGER.log(Level.INFO, null, e);
                }
            }


            Set<DocumentLink> links = new HashSet<>();
            for (DocumentLink link : lastPartI.getLinkedDocuments()) {
                DocumentLink newLink = link.clone();
                links.add(newLink);
            }
            firstPartI.setLinkedDocuments(links);

            Map<String, InstanceAttribute> attrs = new HashMap<>();
            for (InstanceAttribute attr : lastPartI.getInstanceAttributes().values()) {
                InstanceAttribute clonedAttribute = attr.clone();
                attrs.put(clonedAttribute.getName(), clonedAttribute);
            }
            firstPartI.setInstanceAttributes(attrs);

        }


        if (pWorkflowModelId != null) {

            UserDAO userDAO = new UserDAO(new Locale(user.getLanguage()),em);
            RoleDAO roleDAO = new RoleDAO(new Locale(user.getLanguage()),em);

            Map<Role,User> roleUserMap = new HashMap<>();

            for (Object o : roleMappings.entrySet()) {
                Map.Entry pairs = (Map.Entry) o;
                String roleName = (String) pairs.getKey();
                String userLogin = (String) pairs.getValue();
                User worker = userDAO.loadUser(new UserKey(originalPartR.getWorkspaceId(), userLogin));
                Role role = roleDAO.loadRole(new RoleKey(originalPartR.getWorkspaceId(), roleName));
                roleUserMap.put(role, worker);
            }

            WorkflowModel workflowModel = new WorkflowModelDAO(new Locale(user.getLanguage()), em).loadWorkflowModel(new WorkflowModelKey(user.getWorkspaceId(), pWorkflowModelId));
            Workflow workflow = workflowModel.createWorkflow(roleUserMap);
            partR.setWorkflow(workflow);

            Collection<Task> runningTasks = workflow.getRunningTasks();
            for (Task runningTask : runningTasks) {
                runningTask.start();
            }
            mailer.sendApproval(runningTasks, partR);
        }

        partR.setDescription(pDescription);

        if ((pACLUserEntries != null && pACLUserEntries.length > 0) || (pACLUserGroupEntries != null && pACLUserGroupEntries.length > 0)) {
            ACL acl = new ACL();
            if (pACLUserEntries != null) {
                for (ACLUserEntry entry : pACLUserEntries) {
                    acl.addEntry(em.getReference(User.class, new UserKey(user.getWorkspaceId(), entry.getPrincipalLogin())), entry.getPermission());
                }
            }

            if (pACLUserGroupEntries != null) {
                for (ACLUserGroupEntry entry : pACLUserGroupEntries) {
                    acl.addEntry(em.getReference(UserGroup.class, new UserGroupKey(user.getWorkspaceId(), entry.getPrincipalId())), entry.getPermission());
                }
            }
            partR.setACL(acl);
        }
        Date now = new Date();
        partR.setCreationDate(now);
        partR.setCheckOutUser(user);
        partR.setCheckOutDate(now);
        firstPartI.setCreationDate(now);

        partRevisionDAO.createPartR(partR);

        return partR;

    }

    @RolesAllowed("users")
    @Override
    public ConfigSpec getLatestConfigSpec(String workspaceId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException{
        User user = userManager.checkWorkspaceReadAccess(workspaceId);
        return new LatestConfigSpec(user);
    }

    @RolesAllowed("users")
    @Override
    public ConfigSpec getLatestReleasedConfigSpec(String workspaceId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException{
        User user = userManager.checkWorkspaceReadAccess(workspaceId);
        return new LatestReleasedConfigSpec(user);
    }

    @RolesAllowed("users")
    @Override
    public ConfigSpec getConfigSpecForBaseline(int baselineId) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, BaselineNotFoundException {
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ProductBaseline productBaseline = productBaselineDAO.loadBaseline(baselineId);
        User user = userManager.checkWorkspaceReadAccess(productBaseline.getConfigurationItem().getWorkspaceId());
        return new BaselineConfigSpec(productBaseline, user);
    }

    @RolesAllowed("users")
    @Override
    public void deleteBaseline(int baselineId) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, BaselineNotFoundException {
        ProductBaselineDAO productBaselineDAO = new ProductBaselineDAO(em);
        ProductBaseline productBaseline = productBaselineDAO.loadBaseline(baselineId);
        userManager.checkWorkspaceWriteAccess(productBaseline.getConfigurationItem().getWorkspaceId());
        productBaselineDAO.deleteBaseline(productBaseline);
    }

    private List<PartRevision> fillBaselineParts(ProductBaseline productBaseline, PartIteration lastIteration, ProductBaseline.BaselineType type, Locale locale) throws ConfigurationItemNotReleasedException, UserNotFoundException, WorkspaceNotFoundException, UserNotActiveException, PartIterationNotFoundException, NotAllowedException{
        // Ignore already existing parts
        List<PartRevision> ignoredRevisions = new ArrayList<>();
        if(productBaseline.hasBasedLinedPart(lastIteration.getWorkspaceId(), lastIteration.getPartNumber())) return ignoredRevisions;
        // Add current
        productBaseline.addBaselinedPart(lastIteration);
        // Add components
        for(PartUsageLink partUsageLink : lastIteration.getComponents()){
            PartRevision lastRevision;
            PartIteration baselinedIteration = null;
            switch(type){
                case RELEASED:
                    lastRevision = partUsageLink.getComponent().getLastReleasedRevision();
                    if(lastRevision==null){
                        throw new ConfigurationItemNotReleasedException(locale, partUsageLink.getComponent().getNumber());
                    }
                    baselinedIteration = lastRevision.getLastIteration();
                    break;
                case LATEST:
                default:
                    List<PartRevision> partRevisions = partUsageLink.getComponent().getPartRevisions();
                    boolean isPartFinded = false;

                    lastRevision =partUsageLink.getComponent().getLastRevision();
                    if(lastRevision.isCheckedOut()){
                        ignoredRevisions.add(lastRevision);
                    }

                    for(int j= partRevisions.size()-1; j>=0 && !isPartFinded;j--){
                        lastRevision = partRevisions.get(j);
                        for(int i= lastRevision.getLastIteration().getIteration(); i>0 && !isPartFinded; i--){
                            try{
                                checkPartIterationForBaseline(new PartIterationKey(lastRevision.getKey(), i));
                                baselinedIteration = lastRevision.getIteration(i);
                                isPartFinded=true;
                            }catch (AccessRightException e){
                                if(!ignoredRevisions.contains(lastRevision)){
                                    ignoredRevisions.add(lastRevision);
                                }
                            }
                        }
                    }
                    if(baselinedIteration==null){
                        throw new NotAllowedException(Locale.getDefault(), "NotAllowedException1");
                    }
                    break;
            }
            List<PartRevision> ignoredUsageLinkRevisions = fillBaselineParts(productBaseline, baselinedIteration, type, locale);
            ignoredRevisions.addAll(ignoredUsageLinkRevisions);
        }
        return ignoredRevisions;
    }


    @RolesAllowed("users")
    @Override
    public String generateId(String pWorkspaceId, String pPartMTemplateId) throws WorkspaceNotFoundException, UserNotFoundException, UserNotActiveException, PartMasterTemplateNotFoundException {

        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);
        PartMasterTemplate template = new PartMasterTemplateDAO(new Locale(user.getLanguage()), em).loadPartMTemplate(new PartMasterTemplateKey(user.getWorkspaceId(), pPartMTemplateId));

        String newId = null;
        try {
            String latestId = new PartMasterDAO(new Locale(user.getLanguage()), em).findLatestPartMId(pWorkspaceId, template.getPartType());
            String inputMask = template.getMask();
            String convertedMask = Tools.convertMask(inputMask);
            newId = Tools.increaseId(latestId, convertedMask);
        } catch (ParseException ex) {
            //may happen when a different mask has been used for the same document type
        } catch (NoResultException ex) {
            //may happen when no document of the specified type has been created
        }
        return newId;

    }


    @RolesAllowed({"users","admin"})
    @Override
    public long getDiskUsageForPartsInWorkspace(String pWorkspaceId) throws WorkspaceNotFoundException, AccessRightException, AccountNotFoundException {
        Account account = userManager.checkAdmin(pWorkspaceId);
        return new PartMasterDAO(new Locale(account.getLanguage()), em).getDiskUsageForPartsInWorkspace(pWorkspaceId);
    }

    @RolesAllowed({"users","admin"})
    @Override
    public long getDiskUsageForPartTemplatesInWorkspace(String pWorkspaceId) throws WorkspaceNotFoundException, AccessRightException, AccountNotFoundException {
        Account account = userManager.checkAdmin(pWorkspaceId);
        return new PartMasterDAO(new Locale(account.getLanguage()), em).getDiskUsageForPartTemplatesInWorkspace(pWorkspaceId);
    }

    @RolesAllowed({"users","admin"})
    @Override
    public PartRevision[] getAllCheckedOutPartRevisions(String pWorkspaceId) throws WorkspaceNotFoundException, AccessRightException, AccountNotFoundException {
        Account account = userManager.checkAdmin(pWorkspaceId);
        List<PartRevision> partRevisions = new PartRevisionDAO(new Locale(account.getLanguage()), em).findAllCheckedOutPartRevisions(pWorkspaceId);
        return partRevisions.toArray(new PartRevision[partRevisions.size()]);
    }

    @RolesAllowed("users")
    @Override
    public PartRevision approveTaskOnPart(String pWorkspaceId, TaskKey pTaskKey, String pComment, String pSignature) throws WorkspaceNotFoundException, TaskNotFoundException, NotAllowedException, UserNotFoundException, UserNotActiveException {
        //TODO no check is made that pTaskKey is from the same workspace than pWorkspaceId
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);

        Task task = new TaskDAO(new Locale(user.getLanguage()), em).loadTask(pTaskKey);
        Workflow workflow = task.getActivity().getWorkflow();
        PartRevision partRevision = new WorkflowDAO(em).getPartTarget(workflow);


        if (!task.getWorker().equals(user)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException14");
        }

        if (!workflow.getRunningTasks().contains(task)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException15");
        }

        if (partRevision.isCheckedOut()) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException17");
        }

        task.approve(pComment, partRevision.getLastIteration().getIteration(), pSignature);

        Collection<Task> runningTasks = workflow.getRunningTasks();
        for (Task runningTask : runningTasks) {
            runningTask.start();
        }
        mailer.sendApproval(runningTasks, partRevision);
        return partRevision;
    }

    @RolesAllowed("users")
    @Override
    public PartRevision rejectTaskOnPart(String pWorkspaceId, TaskKey pTaskKey, String pComment, String pSignature) throws WorkspaceNotFoundException, TaskNotFoundException, NotAllowedException, UserNotFoundException, UserNotActiveException {

        //TODO no check is made that pTaskKey is from the same workspace than pWorkspaceId
        User user = userManager.checkWorkspaceReadAccess(pWorkspaceId);

        Task task = new TaskDAO(new Locale(user.getLanguage()), em).loadTask(pTaskKey);
        Workflow workflow = task.getActivity().getWorkflow();
        PartRevision partRevision = new WorkflowDAO(em).getPartTarget(workflow);

        if (!task.getWorker().equals(user)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException14");
        }

        if (!workflow.getRunningTasks().contains(task)) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException15");
        }

        if (partRevision.isCheckedOut()) {
            throw new NotAllowedException(new Locale(user.getLanguage()), "NotAllowedException17");
        }

        task.reject(pComment, partRevision.getLastIteration().getIteration(), pSignature);

        // Relaunch Workflow ?
        Activity currentActivity = task.getActivity();

        if(currentActivity.isStopped() && currentActivity.getRelaunchActivity() != null){

            WorkflowDAO workflowDAO = new WorkflowDAO(em);

            int relaunchActivityStep  = currentActivity.getRelaunchActivity().getStep();

            // Clone workflow
            Workflow relaunchedWorkflow  = workflow.clone();
            workflowDAO.createWorkflow(relaunchedWorkflow);

            // Move aborted workflow in partM list
            workflow.abort();
            partRevision.addAbortedWorkflows(workflow);

            // Set new workflow on document
            partRevision.setWorkflow(relaunchedWorkflow);

            // Reset some properties
            relaunchedWorkflow.relaunch(relaunchActivityStep);

            // Send mails for running tasks
            mailer.sendApproval(relaunchedWorkflow.getRunningTasks(), partRevision);

            // Send notification for relaunch
            mailer.sendPartRevisionWorkflowRelaunchedNotification(partRevision);

        }

        return partRevision;

    }

    @RolesAllowed({"users"})
    @Override
    public SharedPart createSharedPart(PartRevisionKey pPartRevisionKey, String pPassword, Date pExpireDate) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, PartRevisionNotFoundException, UserNotActiveException {
        User user = userManager.checkWorkspaceWriteAccess(pPartRevisionKey.getPartMaster().getWorkspace());
        SharedPart sharedPart = new SharedPart(user.getWorkspace(), user, pExpireDate, pPassword, getPartRevision(pPartRevisionKey));
        SharedEntityDAO sharedEntityDAO = new SharedEntityDAO(new Locale(user.getLanguage()),em);
        sharedEntityDAO.createSharedPart(sharedPart);
        return sharedPart;
    }

    @RolesAllowed({"users"})
    @Override
    public void deleteSharedPart(SharedEntityKey pSharedEntityKey) throws UserNotFoundException, AccessRightException, WorkspaceNotFoundException, SharedEntityNotFoundException {
        User user = userManager.checkWorkspaceWriteAccess(pSharedEntityKey.getWorkspace());
        SharedEntityDAO sharedEntityDAO = new SharedEntityDAO(new Locale(user.getLanguage()),em);
        SharedPart sharedPart = sharedEntityDAO.loadSharedPart(pSharedEntityKey.getUuid());
        sharedEntityDAO.deleteSharedPart(sharedPart);
    }

    private User checkPartRevisionWriteAccess(PartRevisionKey partRevisionKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException, AccessRightException {
        String workspaceId = partRevisionKey.getPartMaster().getWorkspace();
        User user = userManager.checkWorkspaceReadAccess(workspaceId);
        if(user.isAdministrator()){                                                                                     // Check if it is the workspace's administrator
            return user;
        }
        PartRevision partRevision = new PartRevisionDAO(em).loadPartR(partRevisionKey);
        if(partRevision.getACL()==null){                                                                                // Check if the part haven't ACL
            return userManager.checkWorkspaceWriteAccess(workspaceId);
        }
        if(partRevision.getACL().hasWriteAccess(user)){                                                                 // Check if the ACL grant write access
            return user;
        }
        throw new AccessRightException(new Locale(user.getLanguage()),user);                                            // Else throw a AccessRightException
    }

    @RolesAllowed({"users","admin"})
    @Override
    public User checkPartRevisionReadAccess(PartRevisionKey partRevisionKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartRevisionNotFoundException, AccessRightException {
        String workspaceId = partRevisionKey.getPartMaster().getWorkspace();
        User user = userManager.checkWorkspaceReadAccess(workspaceId);
        Locale locale = new Locale(user.getLanguage());
        if(user.isAdministrator()){                                                                                     // Check if it is the workspace's administrator
            return user;
        }
        PartRevision partRevision = new PartRevisionDAO(locale,em).loadPartR(partRevisionKey);
        if(partRevision.getACL()==null || partRevision.getACL().hasReadAccess(user)){                                   // Check if there are no ACL or if they grant read access
            return user;
        }
        throw new AccessRightException(locale,user);                                                                    // Else throw a AccessRightException
    }

    private User checkPartIterationForBaseline(PartIterationKey partIterationKey) throws UserNotFoundException, UserNotActiveException, WorkspaceNotFoundException, PartIterationNotFoundException, AccessRightException {
        User user = userManager.checkWorkspaceReadAccess(partIterationKey.getWorkspaceId());
        Locale locale = new Locale(user.getLanguage());
        PartIteration partIteration = new PartIterationDAO(locale,em).loadPartI(partIterationKey);
        PartRevision partRevision = partIteration.getPartRevision();
        if((partRevision.getACL()==null || partRevision.getACL().hasReadAccess(user)) &&
                (!partRevision.isCheckedOut() || !partRevision.getLastIteration().equals(partIteration))){              // Check if the ACL grant write access
            return user;
        }
        throw new AccessRightException(locale,user);                                                                    // Else throw a AccessRightException
    }
}
//TODO when using layers and markers, check for concordance
//TODO add a method to update a marker
//TODO use dozer