terraform {
  required_version = ">= 1.6.0, < 2.0.0"
  required_providers { oci = { source = "oracle/oci", version = ">= 6.0.0, < 9.0.0" } }
}
provider "oci" { region = var.region }
variable "region" { type = string
  default = "us-ashburn-1" }
variable "compartment_id" { type = string }
variable "availability_domain" { type = string }
variable "subnet_id" { type = string }
variable "vcn_id" { type = string }
variable "image_id" { type = string }
variable "ssh_authorized_keys" { type = string
  sensitive = true }
variable "client_cidrs" { type = set(string)
  default = [] }
variable "shape" { type = string
  default = "VM.Standard.E4.Flex" }
variable "ocpus" { type = number
  default = 1 }
variable "memory_in_gbs" { type = number
  default = 8 }
resource "oci_core_network_security_group" "controller" {
  compartment_id = var.compartment_id
  vcn_id = var.vcn_id
  display_name = "broccoli-controller"
}
resource "oci_core_network_security_group_security_rule" "api" {
  for_each = var.client_cidrs
  network_security_group_id = oci_core_network_security_group.controller.id
  direction = "INGRESS"
  protocol = "6"
  source = each.value
  source_type = "CIDR_BLOCK"
  tcp_options { destination_port_range { min = 8443
  max = 8444 } }
}
resource "oci_core_network_security_group_security_rule" "egress" {
  network_security_group_id = oci_core_network_security_group.controller.id
  direction = "EGRESS"
  protocol = "6"
  destination = "0.0.0.0/0"
  destination_type = "CIDR_BLOCK"
  tcp_options { destination_port_range { min = 443
  max = 443 } }
}
resource "oci_core_instance" "controller" {
  compartment_id = var.compartment_id
  availability_domain = var.availability_domain
  display_name = "broccoli-controller"
  shape = var.shape
  shape_config { ocpus = var.ocpus
  memory_in_gbs = var.memory_in_gbs }
  source_details { source_type = "image"
  source_id = var.image_id
  boot_volume_size_in_gbs = 50 }
  create_vnic_details { subnet_id = var.subnet_id
  assign_public_ip = false
  nsg_ids = [oci_core_network_security_group.controller.id] }
  metadata = { ssh_authorized_keys = var.ssh_authorized_keys }
  freeform_tags = { project = "broccoli-controller" }
}
output "instance_id" { value = oci_core_instance.controller.id }
output "private_ip" { value = oci_core_instance.controller.private_ip }
