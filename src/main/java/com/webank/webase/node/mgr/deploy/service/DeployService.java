/**
 * Copyright 2014-2020  the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.webank.webase.node.mgr.deploy.service;

import static com.webank.webase.node.mgr.base.code.ConstantCode.SAME_HOST_ERROR;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.webank.webase.node.mgr.base.code.ConstantCode;
import com.webank.webase.node.mgr.base.code.RetCode;
import com.webank.webase.node.mgr.base.enums.DockerImageTypeEnum;
import com.webank.webase.node.mgr.base.enums.FrontStatusEnum;
import com.webank.webase.node.mgr.base.enums.OptionType;
import com.webank.webase.node.mgr.base.exception.NodeMgrException;
import com.webank.webase.node.mgr.base.properties.ConstantProperties;
import com.webank.webase.node.mgr.base.tools.IPUtil;
import com.webank.webase.node.mgr.base.tools.NetUtils;
import com.webank.webase.node.mgr.base.tools.SshTools;
import com.webank.webase.node.mgr.base.tools.ValidateUtil;
import com.webank.webase.node.mgr.chain.ChainService;
import com.webank.webase.node.mgr.deploy.entity.NodeConfig;
import com.webank.webase.node.mgr.deploy.entity.ReqAdd;
import com.webank.webase.node.mgr.deploy.entity.TbAgency;
import com.webank.webase.node.mgr.deploy.entity.TbChain;
import com.webank.webase.node.mgr.deploy.entity.TbConfig;
import com.webank.webase.node.mgr.deploy.entity.TbHost;
import com.webank.webase.node.mgr.deploy.mapper.TbAgencyMapper;
import com.webank.webase.node.mgr.deploy.mapper.TbChainMapper;
import com.webank.webase.node.mgr.deploy.mapper.TbConfigMapper;
import com.webank.webase.node.mgr.deploy.mapper.TbHostMapper;
import com.webank.webase.node.mgr.front.FrontMapper;
import com.webank.webase.node.mgr.front.FrontService;
import com.webank.webase.node.mgr.front.entity.TbFront;
import com.webank.webase.node.mgr.group.GroupService;
import com.webank.webase.node.mgr.group.entity.TbGroup;
import com.webank.webase.node.mgr.node.NodeService;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Component
public class DeployService {

    @Autowired private TbConfigMapper tbConfigMapper;
    @Autowired private TbChainMapper tbChainMapper;
    @Autowired private FrontMapper frontMapper;
    @Autowired private TbAgencyMapper tbAgencyMapper;
    @Autowired private TbHostMapper tbHostMapper;

    @Autowired private AgencyService agencyService;
    @Autowired private HostService hostService;
    @Autowired private GroupService groupService;
    @Autowired private FrontService frontService;
    @Autowired private ChainService chainService;
    @Autowired private NodeAsyncService nodeAsyncService;
    @Autowired private PathService pathService;
    @Autowired private ConstantProperties constantProperties;

    /**
     * Add in v1.4.0 deploy.
     *
     * @param ipConf
     * @param tagId
     * @param rootDirOnHost
     * @param webaseSignAddr
     * @return
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void deployChain(String chainName, String[] ipConf,
                            int tagId, String rootDirOnHost, String webaseSignAddr, byte dockerImageType) throws NodeMgrException {
        DockerImageTypeEnum imageTypeEnum = DockerImageTypeEnum.getById(dockerImageType);
        if (imageTypeEnum == null){
            throw new NodeMgrException(ConstantCode.UNKNOWN_DOCKER_IMAGE_TYPE);
        }

        if (StringUtils.isBlank(chainName)) {
            throw new NodeMgrException(ConstantCode.PARAM_EXCEPTION);
        }

        // check WeBASE Sign accessible
        if (StringUtils.isBlank(webaseSignAddr)
                || ! NetUtils.checkAddress(webaseSignAddr, 2000) ) {
            throw new NodeMgrException(ConstantCode.WEBASE_SIGN_CONFIG_ERROR);
        }

        // generate config files and insert data to db
        this.chainService.generateChainConfig(chainName,ipConf,tagId,rootDirOnHost,webaseSignAddr, imageTypeEnum,
                constantProperties.getSshDefaultUser(), constantProperties.getSshDefaultPort(),
                constantProperties.getDockerDaemonPort() );

        // init host and start node
        this.nodeAsyncService.asyncDeployChain(chainName,OptionType.DEPLOY_CHAIN);
    }



    /**
     *
     * <p>
     * Delete a chain by chain name.
     *
     * @param chainName
     * @return
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RetCode deleteChain(String chainName) {
        log.info("Delete chain:[{}] data in db and config files...", chainName);
        if (StringUtils.isBlank(chainName)) {
            throw new NodeMgrException(ConstantCode.PARAM_EXCEPTION);
        }

        try {
            this.chainService.delete(chainName);
        } catch (IOException e) {
            log.error("Delete chain:[{}] error.", chainName, e);
            throw new NodeMgrException(ConstantCode.DELETE_CHAIN_ERROR);
        }

        return ConstantCode.SUCCESS;
    }

    /**
     * Add a node.
     *
     * @param add
     * @return
     * @throws NodeMgrException
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Pair<RetCode, String> addNodes(ReqAdd add) throws NodeMgrException {
        String chainName = add.getChainName();
        String ip = add.getIp() ;
        int num = add.getNum();
        String agencyName = add.getAgencyName();
        byte dockerImageType = add.getDockerImageType();
        int groupId = add.getGroupId();

        log.info("Add node check chain name:[{}] exists...", chainName);
        TbChain chain = tbChainMapper.getByChainName(chainName);
        if (chain == null) {
            throw new NodeMgrException(ConstantCode.CHAIN_NAME_NOT_EXISTS_ERROR);
        }

        if (IPUtil.isLocal(ip)){
            throw new NodeMgrException(SAME_HOST_ERROR);
        }

        log.info("Add node check ip format:[{}]...", ip);
        if (!ValidateUtil.ipv4Valid(ip)) {
            throw new NodeMgrException(ConstantCode.IP_FORMAT_ERROR);
        }

        log.info("Add node check ip reachable:[{}]...", ip);
        if (!SshTools.connect(ip,constantProperties.sshDefaultUser,constantProperties.sshDefaultPort,constantProperties.getPrivateKey())) {
            throw new NodeMgrException(ConstantCode.HOST_CONNECT_ERROR);
        }

        // select host list by agency id
        List<TbHost> tbHostList = this.hostService.selectHostListByChainId(chain.getId());

        // check host exists by ip
        TbHost tbHostExists = tbHostList.stream().filter(host -> StringUtils.equalsIgnoreCase(ip, host.getIp())).findFirst().orElse(null);

        TbAgency agency = null;
        if (tbHostExists == null) {
            log.info("Add node check num:[{}]...", num);
            if (num <= 0 || num > ConstantProperties.MAX_NODE_ON_HOST) {
                throw new NodeMgrException(ConstantCode.NODES_NUM_EXCEED_MAX_ERROR);
            }

            if (StringUtils.isBlank(agencyName)) {
                // agency name cannot be blank when host ip is new
                throw new NodeMgrException(ConstantCode.AGENCY_NAME_EMPTY_ERROR);
            }

            // check docker image exists
            DockerImageTypeEnum dockerImageTypeEnum = DockerImageTypeEnum.getById(dockerImageType);
            dockerImageTypeEnum = dockerImageTypeEnum == null ? DockerImageTypeEnum.MANUAL : dockerImageTypeEnum;
            if (DockerImageTypeEnum.MANUAL ==  dockerImageTypeEnum){
                this.hostService.checkImageExists(Collections.singleton(ip), constantProperties.getSshDefaultUser(),
                        constantProperties.getSshDefaultPort(), chain.getVersion());
            }


            // a new host IP address, check agency name is new
            agency = this.agencyService.initAgencyIfNew(
                    agencyName, chain.getId(), chainName, chain.getEncryptType());

            // generate sdk config files
            tbHostExists = this.hostService.generateHostSDKAndScp(chain.getEncryptType(),
                    chain.getChainName(), chain.getRootDir(),
                    ip, agency.getId(), agency.getAgencyName(),
                    constantProperties.getSshDefaultUser(),
                    constantProperties.getSshDefaultPort(),
                    constantProperties.getDockerDaemonPort());
        } else {
            // exist host
            agency = this.tbAgencyMapper.getByChainIdAndAgencyName(chain.getId(), tbHostExists.getAgencyName());

            int currentNodeNum = this.frontMapper.countByHostId(tbHostExists.getId());
            if (currentNodeNum + num > ConstantProperties.MAX_NODE_ON_HOST){
                throw new NodeMgrException(ConstantCode.NODES_NUM_EXCEED_MAX_ERROR);
            }
        }

        // init group, if group is new, return true
        Pair<TbGroup, Boolean> isNewGroup = this.groupService.saveOrUpdateNodeCount(groupId,
                num, chain.getId(), chainName);
        TbGroup group = isNewGroup.getKey();
        boolean newGroup = isNewGroup.getValue();

        // init front and node
        try {
            List<TbFront> newFrontList = this.frontService.initFrontAndNode(num, chain,
                    tbHostExists, agency.getId(), agency.getAgencyName(), group, FrontStatusEnum.ADDING);

            // generate related node config files
            this.frontService.updateNodeConfigIniByGroupId(chain, groupId);

            // generate new nodes config files and scp to remote
            this.groupService.generateNewNodesGroupConfigsAndScp(newGroup, chain, groupId,
                    tbHostExists.getIp(), newFrontList, tbHostExists.getSshUser(),tbHostExists.getSshPort());

            // init host
            // start all front on the host
            // restart related front
            this.nodeAsyncService.asyncAddNode(chain,tbHostExists,group,OptionType.MODIFY_CHAIN,newFrontList);
        } catch (Exception e) {
            log.error("Add node error", e);
            throw new NodeMgrException(ConstantCode.ADD_NODE_WITH_UNKNOWN_EXCEPTION_ERROR, e);
        }

        return Pair.of(ConstantCode.SUCCESS, "success");
    }



    /**
     *
     * @param newTagId
     * @param chainName
     * @return
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void upgrade(int newTagId, String chainName) {
        // check tagId existed
        TbConfig newTagConfig = tbConfigMapper.selectByPrimaryKey(newTagId);
        if (newTagConfig == null
                || StringUtils.isBlank(newTagConfig.getConfigValue())) {
            throw new NodeMgrException(ConstantCode.TAG_ID_PARAM_ERROR);
        }

        log.info("Upgrade check chain name:[{}] exists...", chainName);
        TbChain chain = tbChainMapper.getByChainName(chainName);
        if (chain == null) {
            throw new NodeMgrException(ConstantCode.CHAIN_NAME_NOT_EXISTS_ERROR);
        }

        boolean sameTagVersion = StringUtils.equalsIgnoreCase(chain.getVersion(),newTagConfig.getConfigValue());
        if (sameTagVersion){
            throw new NodeMgrException(ConstantCode.UPGRADE_WITH_SAME_TAG_ERROR);
        }

        this.chainService.upgrade(chain,newTagConfig.getConfigValue());
    }

    /**
     * Start a node by nodeId.
     *
     * @param nodeId
     * @return
     */
    public void startNode(String nodeId,OptionType optionType,FrontStatusEnum before,
                          FrontStatusEnum success,FrontStatusEnum failed) {
        this.frontService.restart(nodeId,optionType,before,success,failed);
    }

    /**
     * Stop a node by nodeId.
     *
     * @param nodeId
     * @return
     */
    public void stopNode(String nodeId) {
        // two nodes running at least
        this.frontService.stopNode(nodeId);
    }

    /**
     *  @param nodeId
     * @param deleteHost
     * @param deleteAgency
     * @return
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteNode(String nodeId,
                           boolean deleteHost,
                           boolean deleteAgency ) {
        // remove front
        TbFront front = this.frontMapper.getByNodeId(nodeId);
        if (front == null) {
            throw new NodeMgrException(ConstantCode.NODE_ID_NOT_EXISTS_ERROR);
        }

        final byte encryptType = FrontService.getEncryptType(front.getImageTag());

        // check front status
        if (FrontStatusEnum.isRunning(front.getStatus())) {
            throw new NodeMgrException(ConstantCode.NODE_RUNNING_ERROR);
        }

        TbChain chain = this.tbChainMapper.selectByPrimaryKey(front.getChainId());
        TbHost host = this.tbHostMapper.selectByPrimaryKey(front.getHostId());

        // get delete node's group id list from ./NODES_ROOT/default_chain/ip/node[x]/conf/group.[groupId].genesis
        Path nodePath = this.pathService.getNodeRoot(chain.getChainName(), host.getIp(), front.getHostIndex());
        Set<Integer> groupIdSet = NodeConfig.getGroupIdSet(nodePath,encryptType);
        try {
            // update related node's config.ini file, e.g. p2p
            this.frontService.updateNodeConfigIniByGroupList(chain, groupIdSet);
        } catch (IOException e) {
            log.error("Delete node, update related group:[{}] node's config error ", groupIdSet, e);
            throw new NodeMgrException(ConstantCode.UPDATE_RELATED_NODE_ERROR);
        }
        // move node directory to tmp
        try {
            this.pathService.deleteNode(chain.getChainName(), host.getIp(), front.getHostIndex(), front.getNodeId());
        } catch (IOException e) {
            log.error("Delete node:[{}:{}:{}] config files error.",
                    chain.getChainName(), host.getIp(), front.getHostIndex(), e);
            throw new NodeMgrException(ConstantCode.DELETE_NODE_DIR_ERROR);
        }

        // move node of remote host files to temp directory, e.g./opt/fisco/delete-tmp
        NodeService.mvNodeOnRemoteHost(host.getIp(), host.getRootDir(), chain.getChainName(), front.getHostIndex(),
                front.getNodeId(),host.getSshUser(),host.getSshPort(),constantProperties.getPrivateKey());

        // delete front, node in db
        this.frontService.removeFront(front.getFrontId());

        // delete host
        this.hostService.deleteHostWithNoNode(deleteHost,host.getId());

        // delete agency
        this.agencyService.deleteAgencyWithNoNode(deleteAgency,host.getId());

        // restart related node
        this.nodeAsyncService.asyncRestartRelatedFront(chain.getId(), groupIdSet, OptionType.MODIFY_CHAIN,
                FrontStatusEnum.STARTING,FrontStatusEnum.RUNNING,FrontStatusEnum.STOPPED);
    }

    /**
     *
     * @param chainName
     */
    public int progress(String chainName) {

        log.info("Progress check chain name:[{}] exists...", chainName);
        TbChain chain = tbChainMapper.getByChainName(chainName);
        if (chain == null) {
            throw new NodeMgrException(ConstantCode.CHAIN_NAME_NOT_EXISTS_ERROR);
        }

        return this.chainService.progress(chain);
    }
}

