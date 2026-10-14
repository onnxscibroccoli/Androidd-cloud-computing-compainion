"""OCI worker: immutable admin-owned profiles, instance principals and API retry tokens."""
import asyncio
import json
from pathlib import Path
import oci

def provision(client, profile, job_id):
    details=oci.core.models.LaunchInstanceDetails(
        compartment_id=profile['compartment_id'],availability_domain=profile['availability_domain'],
        display_name='broccoli-'+job_id,shape=profile['shape'],
        shape_config=oci.core.models.LaunchInstanceShapeConfigDetails(ocpus=profile['ocpus'],memory_in_gbs=profile['memory_in_gbs']),
        source_details=oci.core.models.InstanceSourceViaImageDetails(image_id=profile['image_id']),
        create_vnic_details=oci.core.models.CreateVnicDetails(subnet_id=profile['subnet_id'],assign_public_ip=False),
        freeform_tags={'broccoli_job_id':job_id},metadata=profile.get('metadata',{}))
    response=client.launch_instance(details,opc_retry_token=job_id,retry_strategy=oci.retry.DEFAULT_RETRY_STRATEGY)
    # A launch acceptance is a concrete resource ID; running/guest readiness is a separate task.
    return json.dumps({'instance_id':response.data.id,'lifecycle_state':response.data.lifecycle_state,'request_id':response.headers.get('opc-request-id')})

async def run(store, profiles_path):
    profiles=json.loads(Path(profiles_path).read_text())
    signer=await asyncio.to_thread(oci.auth.signers.InstancePrincipalsSecurityTokenSigner)
    client=oci.core.ComputeClient({},signer=signer,timeout=(5,30))
    store.recover('oci')
    while True:
        for job in store.pending('oci'):
            if not store.claim(job['id']): continue
            try:
                args=json.loads(job['arguments'])
                if job['operation']!='provision' or args['profile'] not in profiles:
                    store.finish('oci',job['id'],'REJECTED','Unknown provisioning profile')
                    continue
                output=await asyncio.to_thread(provision,client,profiles[args['profile']],job['id'])
                store.finish('oci',job['id'],'SUCCEEDED',output)
            except Exception as error:
                # A transport timeout can occur after OCI accepted the resource.
                store.finish('oci',job['id'],'UNKNOWN',type(error).__name__+': reconcile OCI tag broccoli_job_id before resubmitting')
        await asyncio.sleep(.2)
