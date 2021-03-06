/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.clouds.azure.asm.connector;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.StreamUtil;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.OperationResponse;
import com.microsoft.windowsazure.core.OperationStatus;
import com.microsoft.windowsazure.core.OperationStatusResponse;
import com.microsoft.windowsazure.core.utils.Base64;
import com.microsoft.windowsazure.core.utils.KeyStoreType;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.management.ManagementClient;
import com.microsoft.windowsazure.management.compute.*;
import com.microsoft.windowsazure.management.compute.models.*;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse;
import com.microsoft.windowsazure.management.network.NetworkManagementClient;
import com.microsoft.windowsazure.management.network.NetworkManagementService;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse;
import com.microsoft.windowsazure.tracing.CloudTracing;
import com.microsoft.windowsazure.tracing.CloudTracingInterceptor;
import jetbrains.buildServer.clouds.CloudException;
import jetbrains.buildServer.clouds.CloudInstanceUserData;
import jetbrains.buildServer.clouds.InstanceStatus;
import jetbrains.buildServer.clouds.azure.AzurePropertiesNames;
import jetbrains.buildServer.clouds.azure.asm.AzureCloudImage;
import jetbrains.buildServer.clouds.azure.asm.AzureCloudImageDetails;
import jetbrains.buildServer.clouds.azure.asm.AzureCloudInstance;
import jetbrains.buildServer.clouds.azure.asm.errors.InvalidCertificateException;
import jetbrains.buildServer.clouds.azure.asm.models.Image;
import jetbrains.buildServer.clouds.azure.connector.ActionIdChecker;
import jetbrains.buildServer.clouds.azure.connector.AzureApiConnectorBase;
import jetbrains.buildServer.clouds.azure.utils.AlphaNumericStringComparator;
import jetbrains.buildServer.clouds.base.connector.AbstractInstance;
import jetbrains.buildServer.clouds.base.errors.CheckedCloudException;
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.BasicHttpEntity;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jdeferred.*;
import org.jdeferred.impl.DefaultDeferredManager;
import org.jdeferred.impl.DeferredObject;
import org.jdeferred.multiple.MultipleResults;
import org.jdeferred.multiple.OneResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.security.Security;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Sergey.Pak
 *         Date: 8/5/2014
 *         Time: 2:13 PM
 */
public class AzureApiConnector extends AzureApiConnectorBase<AzureCloudImage, AzureCloudInstance> implements ActionIdChecker {

    private static final Logger LOG = Logger.getInstance(AzureApiConnector.class.getName());
    private static final ConcurrentMap<String, Map<String, Integer>> DEPLOYMENT_OPS = new ConcurrentHashMap<>();
    private static final int MIN_PORT_NUMBER = 9090;
    private static final int MAX_PORT_NUMBER = 9999;
    private static final URI MANAGEMENT_URI = URI.create("https://management.core.windows.net");
    private static final String PERSISTENT_VM_ROLE = "PersistentVMRole";
    private final KeyStoreType myKeyStoreType;
    private final String mySubscriptionId;
    private final DeferredManager myManager;
    private ComputeManagementClient myClient;
    private NetworkManagementClient myNetworkClient;
    private ManagementClient myManagementClient;

    public AzureApiConnector(@NotNull final String subscriptionId, @NotNull final String managementCertificate) throws InvalidCertificateException {
        mySubscriptionId = subscriptionId;
        myKeyStoreType = KeyStoreType.pkcs12;
        FileOutputStream fOut;
        final String base64pw;
        final File tempFile;
        try {
            tempFile = File.createTempFile("azk", null);
            fOut = new FileOutputStream(tempFile);
            Random r = new Random();
            byte[] pwdData = new byte[4];
            r.nextBytes(pwdData);
            base64pw = Base64.encode(pwdData).substring(0, 6);
        } catch (IOException e) {
            LOG.warn("An exception while trying to create an API connector", e);
            throw new RuntimeException(e);
        }

        try {
            createKeyStorePKCS12(managementCertificate, fOut, base64pw);
        } catch (Exception e) {
            throw new InvalidCertificateException(e);
        }

        initClient(tempFile, base64pw);

        myManager = new DefaultDeferredManager();
    }

    private void initClient(@NotNull final File keyStoreFile, @NotNull final String keyStoreFilePw) {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
            Configuration myConfiguration = prepareConfiguration(keyStoreFile, keyStoreFilePw, myKeyStoreType);
            myClient = ComputeManagementService.create(myConfiguration);
            myManagementClient = myConfiguration.create(ManagementClient.class);
            myNetworkClient = NetworkManagementService.create(myConfiguration);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Override
    public void test() throws CheckedCloudException {
        final HostedServiceOperations servicesOps = myClient.getHostedServicesOperations();
        final long l = System.currentTimeMillis();
        try {
            servicesOps.checkNameAvailability("abstract_" + l);
        } catch (Exception e) {
            throw new CloudException("Ping error", e);
        }
    }

    @Nullable
    @Override
    public InstanceStatus getInstanceStatusIfExists(@NotNull AzureCloudInstance instance) {
        final Map<String, AzureInstance> instanceMap = listImageInstances(instance.getImage());
        final AzureInstance instanceData = instanceMap.get(instance.getInstanceId());
        if (instanceData == null) {
            return null;
        }

        return instanceData.getInstanceStatus();
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public <R extends AbstractInstance> Map<AzureCloudImage, Map<String, R>> fetchInstances(@NotNull Collection<AzureCloudImage> images) throws CheckedCloudException {
        final HashMap<AzureCloudImage, Map<String, R>> map = new HashMap<>();

        for (AzureCloudImage image : images) {
            final Map<String, AzureInstance> instances = listImageInstances(image);
            map.put(image, (Map<String, R>) instances);
        }

        return map;
    }

    public Map<String, AzureInstance> listImageInstances(@NotNull final AzureCloudImage image) throws CloudException {
        try {
            final AzureCloudImageDetails imageDetails = image.getImageDetails();
            final Map<String, AzureInstance> retval = new HashMap<>();
            final HostedServiceGetDetailedResponse serviceDetailed = myClient.getHostedServicesOperations().getDetailed(imageDetails.getServiceName());
            final ArrayList<HostedServiceGetDetailedResponse.Deployment> deployments = serviceDetailed.getDeployments();

            // there can be one or 0 deployments
            for (final HostedServiceGetDetailedResponse.Deployment deployment : deployments) {
                for (final RoleInstance instance : deployment.getRoleInstances()) {
                    if (imageDetails.getBehaviour().isUseOriginal()) {
                        if (instance.getInstanceName().equals(imageDetails.getSourceName())) {
                            return Collections.singletonMap(imageDetails.getSourceName(), new AzureInstance(instance));
                        }
                    } else {
                        if (instance.getInstanceName().startsWith(imageDetails.getVmNamePrefix())) {
                            retval.put(instance.getInstanceName(), new AzureInstance(instance));
                        }
                    }
                }
            }
            return retval;
        } catch (Exception e) {
            throw new CloudException("Unable to list image instances", e);
        }
    }

    public Promise<Map<String, String>, Throwable, Void> listVmSizesAsync() {
        return myManager.when(myManagementClient.getRoleSizesOperations().listAsync()).then(new DonePipe<RoleSizeListResponse, Map<String, String>, Throwable, Void>() {
            @Override
            public Promise<Map<String, String>, Throwable, Void> pipeDone(RoleSizeListResponse roleSizes) {
                final Comparator<String> comparator = new AlphaNumericStringComparator();
                final List<RoleSizeListResponse.RoleSize> sizes = roleSizes.getRoleSizes();
                Collections.sort(sizes, new Comparator<RoleSizeListResponse.RoleSize>() {
                    @Override
                    public int compare(RoleSizeListResponse.RoleSize o1, RoleSizeListResponse.RoleSize o2) {
                        final String size1 = o1.getName();
                        final String size2 = o2.getName();
                        return comparator.compare(size1, size2);
                    }
                });

                final Map<String, String> map = new LinkedHashMap<>();
                for (RoleSizeListResponse.RoleSize roleSize : sizes) {
                    map.put(roleSize.getName(), roleSize.getLabel());
                }

                return new DeferredObject<Map<String, String>, Throwable, Void>().resolve(map);
            }
        });
    }

    public Promise<Map<String, Map<String, String>>, Throwable, Void> listServicesAsync() {
        return myManager.when(myClient.getHostedServicesOperations().listAsync()).then(new DonePipe<HostedServiceListResponse, Map<String, Map<String, String>>, Throwable, Void>() {
            @Override
            public Promise<Map<String, Map<String, String>>, Throwable, Void> pipeDone(HostedServiceListResponse list) {
                final List<Promise<Pair<String, Map<String, String>>, Throwable, Void>> promises = new ArrayList<>();
                for (HostedServiceListResponse.HostedService service : list) {
                    promises.add(listServiceInstancesAsync(service.getServiceName()));
                }

                final Map<String, Map<String, String>> services = new HashMap<>();
                if (promises.size() == 0) {
                    return new DeferredObject<Map<String, Map<String, String>>, Throwable, Void>().resolve(services);
                }

                return myManager.when(promises.toArray(new Promise[]{})).then(new DonePipe<MultipleResults, Map<String, Map<String, String>>, Throwable, Void>() {
                    @Override
                    public Promise<Map<String, Map<String, String>>, Throwable, Void> pipeDone(MultipleResults results) {
                        for (OneResult result : results) {
                            //noinspection unchecked
                            final Pair<String, Map<String, String>> data = (Pair<String, Map<String, String>>) result.getResult();
                            services.put(data.getFirst(), data.getSecond());
                        }

                        return new DeferredObject<Map<String, Map<String, String>>, Throwable, Void>().resolve(services);
                    }
                });
            }
        });
    }

    public Promise<List<String>, Throwable, Void> listVirtualNetworksAsync() {
        return myManager.when(myNetworkClient.getNetworksOperations().listAsync()).then(new DonePipe<NetworkListResponse, List<String>, Throwable, Void>() {
            @Override
            public Promise<List<String>, Throwable, Void> pipeDone(final NetworkListResponse virtualNetworkSites) {
                List<String> networks = new ArrayList<String>() {{
                    for (NetworkListResponse.VirtualNetworkSite virtualNetworkSite : virtualNetworkSites) {
                        add(virtualNetworkSite.getName());
                    }
                }};

                return new DeferredObject<List<String>, Throwable, Void>().resolve(networks);
            }
        });
    }

    private Promise<Pair<String, Map<String, String>>, Throwable, Void> listServiceInstancesAsync(@NotNull final String serviceName) {
        return myManager.when(myClient.getHostedServicesOperations().getDetailedAsync(serviceName)).then(new DonePipe<HostedServiceGetDetailedResponse, Pair<String, Map<String, String>>, Throwable, Void>() {
            @Override
            public Promise<Pair<String, Map<String, String>>, Throwable, Void> pipeDone(HostedServiceGetDetailedResponse response) {
                final Map<String, String> instances = new HashMap<>();
                for (HostedServiceGetDetailedResponse.Deployment deployment : response.getDeployments()) {
                    final Map<String, String> roleOsNames = new HashMap<>();
                    for (Role role : deployment.getRoles()) {
                        if (role.getOSVirtualHardDisk() != null)
                        {
                            roleOsNames.put(role.getRoleName(), role.getOSVirtualHardDisk().getOperatingSystem());
                        }
                    }
                    for (RoleInstance instance : deployment.getRoleInstances()) {
                        instances.put(instance.getInstanceName(), roleOsNames.get(instance.getRoleName()));
                    }
                }

                return new DeferredObject<Pair<String, Map<String, String>>, Throwable, Void>().resolve(new Pair<>(serviceName, instances));
            }
        });
    }

    public Promise<List<Image>, Throwable, Void> listImagesAsync() {
        return myManager.when(myClient.getVirtualMachineVMImagesOperations().listAsync()).then(new DonePipe<VirtualMachineVMImageListResponse, List<Image>, Throwable, Void>() {
            @Override
            public Promise<List<Image>, Throwable, Void> pipeDone(final VirtualMachineVMImageListResponse imagesList) {
                final List<Image> images = new ArrayList<Image>() {{
                    for (VirtualMachineVMImageListResponse.VirtualMachineVMImage image : imagesList) {
                        if (!"User".equals(image.getCategory())) continue;
                        add(new Image(image.getName(), image.getLabel(), image.getOSDiskConfiguration().getOperatingSystem(), isImageGeneralized(image)));
                    }
                }};

                final Comparator<String> comparator = new AlphaNumericStringComparator();
                Collections.sort(images, new Comparator<Image>() {
                    @Override
                    public int compare(Image o1, Image o2) {
                        final String label1 = o1.getLabel();
                        final String label2 = o2.getLabel();
                        return comparator.compare(label1, label2);
                    }
                });

                return new DeferredObject<List<Image>, Throwable, Void>().resolve(images);
            }
        });
    }

    public OperationResponse startVM(@NotNull final AzureCloudImage image) throws ServiceException, IOException {
        final AzureCloudImageDetails imageDetails = image.getImageDetails();
        final VirtualMachineOperations vmOps = myClient.getVirtualMachinesOperations();
        final String serviceName = imageDetails.getServiceName();
        final HostedServiceGetDetailedResponse.Deployment serviceDeployment = getServiceDeployment(serviceName);
        if (serviceDeployment != null)
            return vmOps.beginStarting(serviceName, serviceDeployment.getName(), imageDetails.getSourceName());
        else
            throw new ServiceException(String.format("Unable to find deployment for service name '%s' and instance '%s'", serviceName, image.getName()));
    }

    public OperationResponse createVmOrDeployment(@NotNull final AzureCloudImage image,
                                                  @NotNull final String vmName,
                                                  @NotNull final CloudInstanceUserData tag,
                                                  final boolean generalized) throws ServiceException, IOException {
        final AzureCloudImageDetails imageDetails = image.getImageDetails();
        final HostedServiceGetDetailedResponse.Deployment serviceDeployment = getServiceDeployment(imageDetails.getServiceName());
        if (serviceDeployment == null) {
            return createVmDeployment(imageDetails, generalized, vmName, tag);
        } else {
            return createVM(imageDetails, generalized, vmName, tag, serviceDeployment);
        }
    }

    private OperationResponse createVM(final AzureCloudImageDetails imageDetails,
                                       final boolean generalized,
                                       final String vmName,
                                       final CloudInstanceUserData tag,
                                       final HostedServiceGetDetailedResponse.Deployment deployment) throws ServiceException, IOException {
        final String serviceName = imageDetails.getServiceName();
        final VirtualMachineCreateParameters parameters = new VirtualMachineCreateParameters();
        parameters.setRoleSize(imageDetails.getVmSize());
        parameters.setProvisionGuestAgent(Boolean.TRUE);
        parameters.setRoleName(vmName);
        parameters.setVMImageName(imageDetails.getSourceName());

        final int portNumber = imageDetails.getPublicIp() ? getPortNumber(serviceName, deployment) : 0;
        final ArrayList<ConfigurationSet> configurationSetList = createConfigurationSetList(imageDetails, generalized, vmName, tag, portNumber);
        parameters.setConfigurationSets(configurationSetList);

        final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
        try {
            final OperationResponse response = vmOperations.beginCreating(serviceName, deployment.getName(), parameters);
            addDeploymentOperation(serviceName, response.getRequestId(), portNumber);
            return response;
        } catch (ParserConfigurationException e) {
            throw new ServiceException(e);
        } catch (SAXException | TransformerException e) {
            throw new IOException(e);
        }
    }

    private int getPortNumber(final String serviceName, final HostedServiceGetDetailedResponse.Deployment deployment) {
        final BitSet busyPorts = new BitSet();
        busyPorts.set(MIN_PORT_NUMBER, MAX_PORT_NUMBER);

        for (RoleInstance instance : deployment.getRoleInstances()) {
            for (InstanceEndpoint endpoint : instance.getInstanceEndpoints()) {
                final int port = endpoint.getPort();
                if (port >= MIN_PORT_NUMBER && port <= MAX_PORT_NUMBER) {
                    busyPorts.set(port, false);
                }
            }
        }

        for (Role role : deployment.getRoles()) {
            for (ConfigurationSet conf : role.getConfigurationSets()) {
                for (InputEndpoint endpoint : conf.getInputEndpoints()) {
                    final int port = endpoint.getPort();
                    if (port >= MIN_PORT_NUMBER && port <= MAX_PORT_NUMBER) {
                        busyPorts.set(port, false);
                    }
                }
            }
        }

        final Map<String, Integer> map = DEPLOYMENT_OPS.get(serviceName);
        if (map != null) {
            final Iterator<String> iter = map.keySet().iterator();
            while (iter.hasNext()) {
                final String operationId = iter.next();
                if (isActionFinished(operationId)) {
                    iter.remove();
                } else {
                    busyPorts.set(map.get(operationId), false);
                }
            }
        }

        int portNumber = MIN_PORT_NUMBER;
        for (int i = MIN_PORT_NUMBER; i <= MAX_PORT_NUMBER; i++) {
            if (busyPorts.get(i)) {
                portNumber = i;
                break;
            }
        }

        return portNumber;
    }

    private OperationResponse createVmDeployment(final AzureCloudImageDetails imageDetails,
                                                 final boolean generalized,
                                                 final String vmName,
                                                 final CloudInstanceUserData tag) throws IOException, ServiceException {
        final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
        final VirtualMachineCreateDeploymentParameters vmDeployParams = new VirtualMachineCreateDeploymentParameters();

        final Role role = new Role();
        role.setVMImageName(imageDetails.getSourceName());
        role.setRoleType(PERSISTENT_VM_ROLE);
//    role.setRoleType(VirtualMachineRoleType.PERSISTENTVMROLE.name());
        role.setRoleName(vmName);
        role.setProvisionGuestAgent(true);
        role.setRoleSize(imageDetails.getVmSize());
        role.setLabel(imageDetails.getSourceName());

        final int portNumber = imageDetails.getPublicIp() ? MIN_PORT_NUMBER : 0;
        role.setConfigurationSets(createConfigurationSetList(imageDetails, generalized, vmName, tag, portNumber));

        final ArrayList<Role> roleAsList = new ArrayList<>();
        roleAsList.add(role);
        vmDeployParams.setRoles(roleAsList);

        final String vnetName = imageDetails.getVnetName();
        if (vnetName != null && vnetName.trim().length() != 0) {
            vmDeployParams.setVirtualNetworkName(vnetName);
        }
        vmDeployParams.setLabel(imageDetails.getSourceName());
        vmDeployParams.setName("teamcityVms");
        vmDeployParams.setDeploymentSlot(DeploymentSlot.Production);

        try {
            final String serviceName = imageDetails.getServiceName();
            final OperationResponse response = vmOperations.beginCreatingDeployment(serviceName, vmDeployParams);
            addDeploymentOperation(serviceName, response.getRequestId(), portNumber);
            return response;
        } catch (ParserConfigurationException | SAXException | TransformerException e) {
            throw new IOException(e);
        }
    }

    private void addDeploymentOperation(final String serviceName, final String requestId, final int portNumber) {
        if (portNumber > 0) {
            Map<String, Integer> map = DEPLOYMENT_OPS.get(serviceName);
            if (map == null) {
                final Map<String, Integer> value = new HashMap<>();
                map = DEPLOYMENT_OPS.putIfAbsent(serviceName, value);
                if (map == null) {
                    map = value;
                }
            }
            map.put(requestId, portNumber);
        }
    }

    private ArrayList<ConfigurationSet> createConfigurationSetList(final AzureCloudImageDetails imageDetails,
                                                                   final boolean generalized,
                                                                   final String vmName,
                                                                   final CloudInstanceUserData tag,
                                                                   final int portNumber) {
        final ArrayList<ConfigurationSet> configurationSetList = createConfigurationSetList(portNumber, tag.getServerAddress());
        if (generalized) {
            ConfigurationSet provisionConf = new ConfigurationSet();
            configurationSetList.add(provisionConf);

            final String serializedUserData = tag.serialize();
            if ("Linux".equals(imageDetails.getOsType())) {
                provisionConf.setConfigurationSetType(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION);
                provisionConf.setHostName(vmName);
                provisionConf.setUserName(imageDetails.getUsername());
                provisionConf.setUserPassword(imageDetails.getPassword());
                // for Linux userData is written to xml config as is - in Base64 format. We will decode it using CloudInstanceUserData.decode
                provisionConf.setCustomData(serializedUserData);
            } else {
                provisionConf.setConfigurationSetType(ConfigurationSetTypes.WINDOWSPROVISIONINGCONFIGURATION);
                provisionConf.setComputerName(vmName);
                provisionConf.setAdminUserName(imageDetails.getUsername());
                provisionConf.setAdminPassword(imageDetails.getPassword());
                // for Windows userData is decoded from Base64 format and written to C:\AzureData\CustomData.bin
                // that's why we additionally encode it in Base64 again
                provisionConf.setCustomData(Base64.encode(serializedUserData.getBytes()));
            }
        }
        return configurationSetList;
    }


    public OperationResponse stopVM(@NotNull final AzureCloudInstance instance)
            throws ServiceException, IOException {
        final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
        final AzureCloudImageDetails imageDetails = instance.getImage().getImageDetails();
        final VirtualMachineShutdownParameters shutdownParams = new VirtualMachineShutdownParameters();
        shutdownParams.setPostShutdownAction(PostShutdownAction.StoppedDeallocated);
        final HostedServiceGetDetailedResponse.Deployment serviceDeployment = getServiceDeployment(imageDetails.getServiceName());
        if (serviceDeployment != null) {
            try {
                return vmOperations.beginShutdown(imageDetails.getServiceName(), serviceDeployment.getName(), instance.getName(), shutdownParams);
            } catch (ParserConfigurationException | SAXException | TransformerException e) {
                throw new CloudException(e.getMessage(), e);
            }
        } else {
            throw new CloudException(String.format("Unable to find deployment for service '%s' and instance '%s'",
                    imageDetails.getServiceName(), instance.getName()));
        }
    }

    public OperationResponse deleteVmOrDeployment(@NotNull final AzureCloudInstance instance) throws IOException, ServiceException {
        final HostedServiceOperations serviceOps = myClient.getHostedServicesOperations();
        final AzureCloudImageDetails imageDetails = instance.getImage().getImageDetails();
        try {
            final String serviceName = imageDetails.getServiceName();
            final ArrayList<HostedServiceGetDetailedResponse.Deployment> deployments = serviceOps.getDetailed(serviceName).getDeployments();
            if (deployments.size() == 1) {
                final HostedServiceGetDetailedResponse.Deployment deployment = deployments.get(0);
                if (deployment.getRoleInstances().size() == 1) {
                    return deleteVmDeployment(serviceName, deployment.getName());
                } else {
                    return deleteVM(serviceName, deployment.getName(), instance.getName());
                }
            } else {
                String msg = String.format("Invalid # of deployments (%d) while trying to delete instance '%s'", deployments.size(), instance.getName());
                throw new ServiceException(msg);
            }
        } catch (ParserConfigurationException | SAXException | URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private OperationResponse deleteVM(final String serviceName, final String deploymentName, final String instanceName) throws IOException, ServiceException {
        final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
        return vmOperations.beginDeleting(serviceName, deploymentName, instanceName, true);
    }

    private OperationResponse deleteVmDeployment(final String serviceName, final String deploymentName) throws IOException, ServiceException {
        final DeploymentOperations deployOps = myClient.getDeploymentsOperations();
        return deployOps.beginDeletingByName(serviceName, deploymentName, true);
    }

    public OperationStatusResponse getOperationStatus(@NotNull final String operationId) throws ServiceException, ParserConfigurationException, SAXException, IOException {
        return myClient.getOperationStatus(operationId);
    }

    @Nullable
    private HostedServiceGetDetailedResponse.Deployment getServiceDeployment(String serviceName)
            throws IOException, ServiceException {
        final HostedServiceOperations serviceOps = myClient.getHostedServicesOperations();
        try {
            final HostedServiceGetDetailedResponse detailed = serviceOps.getDetailed(serviceName);
            final ArrayList<HostedServiceGetDetailedResponse.Deployment> deployments = detailed.getDeployments();
            if (deployments.size() == 0) {
                return null;
            } else if (deployments.size() == 1) {
                final HostedServiceGetDetailedResponse.Deployment deployment = deployments.get(0);
                final ArrayList<Role> roles = deployment.getRoles();
                if (roles.size() == 0) {
                    return deployment;
                }
                final Role role = roles.get(0);
                if (PERSISTENT_VM_ROLE.equals(role.getRoleType()))
                    return deployment;
                else
                    throw new ServiceException("Service is not suitable for VM deployment");
            } else {
                throw new ServiceException(String.format("Wrong # of deployments (%d) for service '%s'", deployments.size(), serviceName));
            }
        } catch (ParserConfigurationException | SAXException | URISyntaxException e) {
            throw new IOException(e);
        }

    }

    public boolean isImageGeneralized(@NotNull final String imageName) {
        final VirtualMachineVMImageOperations vmImagesOperations = myClient.getVirtualMachineVMImagesOperations();
        try {
            for (VirtualMachineVMImageListResponse.VirtualMachineVMImage image : vmImagesOperations.list()) {
                if (imageName.equals(image.getName())) {
                    return isImageGeneralized(image);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new RuntimeException("Unable to find image with name " + imageName);
    }

    private boolean isImageGeneralized(final VirtualMachineVMImageListResponse.VirtualMachineVMImage image) {
        return "Generalized".equals(image.getOSDiskConfiguration().getOSState());
    }

    private Configuration prepareConfiguration(@NotNull final File keyStoreFile, @NotNull final String password, @NotNull final KeyStoreType keyStoreType) throws IOException {
        return ManagementConfiguration.configure(MANAGEMENT_URI, mySubscriptionId, keyStoreFile.getPath(), password, keyStoreType);
    }

    private static KeyStore createKeyStorePKCS12(String base64Certificate, OutputStream keyStoreOutputStream, String keystorePwd) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyStore store = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME);
        store.load(null, null);

        // read in the value of the base 64 cert without a password (PBE can be applied afterwards if this is needed
        final byte[] decode = Base64.decode(base64Certificate);
        InputStream sslInputStream = new ByteArrayInputStream(decode);
        store.load(sslInputStream, "".toCharArray());

        // we need to a create a physical keystore as well here
        store.store(keyStoreOutputStream, keystorePwd.toCharArray());
        keyStoreOutputStream.close();
        return store;
    }

    private static ArrayList<ConfigurationSet> createConfigurationSetList(int port, String serverLocation) {
        ArrayList<ConfigurationSet> retval = new ArrayList<>();
        final ConfigurationSet value = new ConfigurationSet();
        value.setConfigurationSetType(ConfigurationSetTypes.NETWORKCONFIGURATION);
        final ArrayList<InputEndpoint> endpointsList = new ArrayList<>();
        value.setInputEndpoints(endpointsList);

        if (port > 0) {
            InputEndpoint endpoint = new InputEndpoint();
            endpointsList.add(endpoint);
            endpoint.setLocalPort(port);
            endpoint.setPort(port);
            endpoint.setProtocol("TCP");
            endpoint.setName(AzurePropertiesNames.ENDPOINT_NAME);
            final EndpointAcl acl = new EndpointAcl();
            endpoint.setEndpointAcl(acl);

            final URI serverUri = URI.create(serverLocation);
            List<InetAddress> serverAddresses = new ArrayList<>();
            try {
                serverAddresses.addAll(Arrays.asList(InetAddress.getAllByName(serverUri.getHost())));
                serverAddresses.add(InetAddress.getLocalHost());
            } catch (UnknownHostException e) {
                LOG.warn("Unable to identify server name ip list", e);
            }
            final ArrayList<AccessControlListRule> aclRules = new ArrayList<>();
            acl.setRules(aclRules);
            int order = 1;
            for (final InetAddress address : serverAddresses) {
                if (!(address instanceof Inet4Address)) {
                    continue;
                }
                final AccessControlListRule rule = new AccessControlListRule();
                rule.setOrder(order++);
                rule.setAction("Permit");
                rule.setRemoteSubnet(address.getHostAddress() + "/32");
                rule.setDescription("Server");
                aclRules.add(rule);
            }
        }

        retval.add(value);
        return retval;
    }

    public boolean isActionFinished(@NotNull final String actionId) {
        final OperationStatusResponse operationStatus;
        try {
            operationStatus = getOperationStatus(actionId);
            final boolean isFinished = operationStatus.getStatus() == OperationStatus.Succeeded || operationStatus.getStatus() == OperationStatus.Failed;
            if (operationStatus.getError() != null) {
                LOG.info(String.format("Was an error during executing action %s: %s", actionId, operationStatus.getError().getMessage()));
            }
            return isFinished;
        } catch (Exception e) {
            LOG.warn(e.toString(), e);
            return false;
        }
    }

    static {
        CloudTracing.addTracingInterceptor(new CloudTracingInterceptor() {
            public void information(String s) {
                LOG.info(s);
            }

            public void configuration(String s, String s1, String s2) {
                LOG.info(String.format("Configuration:%s-%s-%s", s, s1, s2));
            }

            public void enter(String s, Object o, String s1, HashMap<String, Object> hashMap) {
                LOG.info(String.format("Enter: %s, %s", s, s1));
            }

            public void sendRequest(String s, HttpRequest httpRequest) {
                LOG.info(String.format("Request(%s):%n%s", s, httpRequest.getRequestLine().getUri()));
            }

            public void receiveResponse(String s, HttpResponse httpResponse) {
                try {
                    final HttpEntity entity = httpResponse.getEntity();
                    final InputStream content = entity.getContent();
                    final String result = StreamUtil.readText(content);
                    LOG.info(String.format("Request(%s):%n%s", s, result));
                    if (!entity.isRepeatable()) {
                        BasicHttpEntity newEntity = new BasicHttpEntity();
                        newEntity.setContent(new ByteArrayInputStream(result.getBytes()));
                        newEntity.setChunked(entity.isChunked());
                        newEntity.setContentLength(result.getBytes().length);
                        newEntity.setContentType(entity.getContentType());
                        httpResponse.setEntity(newEntity);
                    }
                } catch (IOException e) {
                    LOG.error("Unable to read response entity from %s", s);
                }
            }

            public void error(String s, Exception e) {
                LOG.error(s, e);
            }

            public void exit(String s, Object o) {
                LOG.info("Exit: " + s);
            }
        });
    }

    @NotNull
    @Override
    public TypedCloudErrorInfo[] checkImage(@NotNull AzureCloudImage image) {
        return new TypedCloudErrorInfo[0];
    }

    @NotNull
    @Override
    public TypedCloudErrorInfo[] checkInstance(@NotNull AzureCloudInstance instance) {
        return new TypedCloudErrorInfo[0];
    }
}
