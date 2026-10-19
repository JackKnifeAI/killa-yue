use ash::{vk, Entry};
use serde_json::{json, Value};
use std::ffi::CStr;

mod shader {
    include!("matrix_spirv.rs");
}

fn error<E: std::fmt::Debug>(e: E) -> String {
    format!("{e:?}")
}

// These functions only run inside a disposable child. A bad driver may crash,
// hang, or retain resources on an error path; the parent enforces the deadline
// and the OS reclaims all child resources. Never call them in the UI process.
unsafe fn instance() -> Result<(Entry, ash::Instance), String> {
    let entry = unsafe { Entry::load() }.map_err(error)?;
    let version = unsafe { entry.try_enumerate_instance_version() }
        .map_err(error)?
        .unwrap_or(vk::API_VERSION_1_0);
    let app = vk::ApplicationInfo::default()
        .application_name(c"Killa Desktop Lab")
        .api_version(version.min(vk::API_VERSION_1_1));
    let extensions =
        unsafe { entry.enumerate_instance_extension_properties(None) }.map_err(error)?;
    let portability = extensions
        .iter()
        .any(|e| e.extension_name_as_c_str().ok() == Some(ash::khr::portability_enumeration::NAME));
    let enabled = if portability {
        vec![ash::khr::portability_enumeration::NAME.as_ptr()]
    } else {
        vec![]
    };
    let info = vk::InstanceCreateInfo::default()
        .application_info(&app)
        .enabled_extension_names(&enabled)
        .flags(if portability {
            vk::InstanceCreateFlags::ENUMERATE_PORTABILITY_KHR
        } else {
            vk::InstanceCreateFlags::empty()
        });
    let instance = unsafe { entry.create_instance(&info, None) }.map_err(error)?;
    Ok((entry, instance))
}

fn properties(index: usize, p: &vk::PhysicalDeviceProperties) -> Value {
    json!({
        "index": index,
        "name": p.device_name_as_c_str().map(CStr::to_string_lossy).unwrap_or_default(),
        "vendor_id": p.vendor_id, "device_id": p.device_id,
        "api_version": format!("{}.{}.{}", vk::api_version_major(p.api_version), vk::api_version_minor(p.api_version), vk::api_version_patch(p.api_version)),
        "driver_version_raw": p.driver_version,
        "device_type": format!("{:?}", p.device_type),
        "software_device": p.device_type == vk::PhysicalDeviceType::CPU,
        "max_storage_buffer_bytes": p.limits.max_storage_buffer_range
    })
}

pub fn inventory() -> Value {
    // All pointers are Vulkan-owned structures accessed while instance/entry
    // are live. We never enumerate device UUIDs or pipeline cache identifiers.
    unsafe {
        match inventory_inner() {
            Ok(v) => v,
            Err(e) => json!({"status": "unavailable", "reason": e, "devices": []}),
        }
    }
}

unsafe fn inventory_inner() -> Result<Value, String> {
    let (_entry, instance) = unsafe { instance()? };
    let devices = unsafe { instance.enumerate_physical_devices() }.map_err(error)?;
    let mut result = Vec::new();
    for (index, device) in devices.iter().take(8).enumerate() {
        let p = unsafe { instance.get_physical_device_properties(*device) };
        let mut row = properties(index, &p);
        let queues = unsafe { instance.get_physical_device_queue_family_properties(*device) };
        let memory = unsafe { instance.get_physical_device_memory_properties(*device) };
        row["compute_queue"] = json!(queues
            .iter()
            .any(|q| q.queue_count > 0 && q.queue_flags.contains(vk::QueueFlags::COMPUTE)));
        row["memory_heaps"] = json!(memory.memory_heaps[..memory.memory_heap_count as usize].iter().map(|h| json!({"size_bytes": h.size, "device_local": h.flags.contains(vk::MemoryHeapFlags::DEVICE_LOCAL)})).collect::<Vec<_>>());
        result.push(row);
    }
    unsafe {
        instance.destroy_instance(None);
    }
    Ok(
        json!({"status": if result.is_empty() { "unavailable" } else { "pass" }, "devices": result, "devices_truncated": devices.len() > 8,
        "note": "Enumeration is not compute compatibility. Heap sizes are not free VRAM. CPU/software Vulkan devices are not GPU acceleration."}),
    )
}

pub fn matrix(index: usize) -> Value {
    unsafe {
        match matrix_inner(index) {
            Ok(v) => v,
            Err(e) => json!({"status": "fail", "reason": e}),
        }
    }
}

unsafe fn matrix_inner(index: usize) -> Result<Value, String> {
    if index >= 8 {
        return Err("device index limit".into());
    }
    let (_entry, instance) = unsafe { instance()? };
    let devices = unsafe { instance.enumerate_physical_devices() }.map_err(error)?;
    let physical = *devices.get(index).ok_or("device unavailable")?;
    let props = unsafe { instance.get_physical_device_properties(physical) };
    let queues = unsafe { instance.get_physical_device_queue_family_properties(physical) };
    let family = queues
        .iter()
        .position(|q| q.queue_count > 0 && q.queue_flags.contains(vk::QueueFlags::COMPUTE))
        .ok_or("no compute queue")? as u32;
    let priorities = [1.0];
    let queue_info = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(family)
        .queue_priorities(&priorities)];
    let extensions =
        unsafe { instance.enumerate_device_extension_properties(physical) }.map_err(error)?;
    let portability = extensions
        .iter()
        .any(|e| e.extension_name_as_c_str().ok() == Some(ash::khr::portability_subset::NAME));
    let enabled = if portability {
        vec![ash::khr::portability_subset::NAME.as_ptr()]
    } else {
        vec![]
    };
    let info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_info)
        .enabled_extension_names(&enabled);
    let device = unsafe { instance.create_device(physical, &info, None) }.map_err(error)?;
    let queue = unsafe { device.get_device_queue(family, 0) };
    let mut data = crate::math::inputs();
    let size = (data.len() * std::mem::size_of::<f32>()) as u64;
    let buffer = unsafe {
        device.create_buffer(
            &vk::BufferCreateInfo::default()
                .size(size)
                .usage(vk::BufferUsageFlags::STORAGE_BUFFER)
                .sharing_mode(vk::SharingMode::EXCLUSIVE),
            None,
        )
    }
    .map_err(error)?;
    let required = unsafe { device.get_buffer_memory_requirements(buffer) };
    if required.size > 4 * 1024 * 1024 {
        return Err("test allocation exceeds 4 MiB limit".into());
    }
    let memory_props = unsafe { instance.get_physical_device_memory_properties(physical) };
    let memory_type = (0..memory_props.memory_type_count)
        .find(|&i| {
            required.memory_type_bits & (1 << i) != 0
                && memory_props.memory_types[i as usize]
                    .property_flags
                    .contains(
                        vk::MemoryPropertyFlags::HOST_VISIBLE
                            | vk::MemoryPropertyFlags::HOST_COHERENT,
                    )
        })
        .ok_or("no host-visible coherent storage memory; this probe cannot test this device")?;
    let memory = unsafe {
        device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(required.size)
                .memory_type_index(memory_type),
            None,
        )
    }
    .map_err(error)?;
    unsafe { device.bind_buffer_memory(buffer, memory, 0) }.map_err(error)?;
    let mapped = unsafe { device.map_memory(memory, 0, size, vk::MemoryMapFlags::empty()) }
        .map_err(error)? as *mut f32;
    unsafe {
        std::ptr::copy_nonoverlapping(data.as_ptr(), mapped, data.len());
    }

    let binding = [vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::COMPUTE)];
    let set_layout = unsafe {
        device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&binding),
            None,
        )
    }
    .map_err(error)?;
    let layouts = [set_layout];
    let layout = unsafe {
        device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts),
            None,
        )
    }
    .map_err(error)?;
    let module = unsafe {
        device.create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(shader::MATRIX_SPIRV),
            None,
        )
    }
    .map_err(error)?;
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(module)
        .name(c"main");
    let pipeline_info = [vk::ComputePipelineCreateInfo::default()
        .stage(stage)
        .layout(layout)];
    let pipeline =
        unsafe { device.create_compute_pipelines(vk::PipelineCache::null(), &pipeline_info, None) }
            .map_err(|(_, e)| error(e))?[0];
    let pool_sizes = [vk::DescriptorPoolSize {
        ty: vk::DescriptorType::STORAGE_BUFFER,
        descriptor_count: 1,
    }];
    let pool = unsafe {
        device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .max_sets(1)
                .pool_sizes(&pool_sizes),
            None,
        )
    }
    .map_err(error)?;
    let set = unsafe {
        device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(pool)
                .set_layouts(&layouts),
        )
    }
    .map_err(error)?[0];
    let buffers = [vk::DescriptorBufferInfo::default()
        .buffer(buffer)
        .offset(0)
        .range(size)];
    let writes = [vk::WriteDescriptorSet::default()
        .dst_set(set)
        .dst_binding(0)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(&buffers)];
    unsafe {
        device.update_descriptor_sets(&writes, &[]);
    }
    let command_pool = unsafe {
        device.create_command_pool(
            &vk::CommandPoolCreateInfo::default().queue_family_index(family),
            None,
        )
    }
    .map_err(error)?;
    let commands = unsafe {
        device.allocate_command_buffers(
            &vk::CommandBufferAllocateInfo::default()
                .command_pool(command_pool)
                .level(vk::CommandBufferLevel::PRIMARY)
                .command_buffer_count(1),
        )
    }
    .map_err(error)?;
    let command = commands[0];
    unsafe {
        device
            .begin_command_buffer(command, &vk::CommandBufferBeginInfo::default())
            .map_err(error)?;
        let upload = [vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::HOST_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)];
        device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::HOST,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &upload,
            &[],
            &[],
        );
        device.cmd_bind_pipeline(command, vk::PipelineBindPoint::COMPUTE, pipeline);
        device.cmd_bind_descriptor_sets(
            command,
            vk::PipelineBindPoint::COMPUTE,
            layout,
            0,
            &[set],
            &[],
        );
        device.cmd_dispatch(command, 2, 1, 1);
        let download = [vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::HOST_READ)];
        device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::HOST,
            vk::DependencyFlags::empty(),
            &download,
            &[],
            &[],
        );
        device.end_command_buffer(command).map_err(error)?;
    }
    let fence =
        unsafe { device.create_fence(&vk::FenceCreateInfo::default(), None) }.map_err(error)?;
    let submits = [vk::SubmitInfo::default().command_buffers(&commands)];
    unsafe {
        device.queue_submit(queue, &submits, fence).map_err(error)?;
        device
            .wait_for_fences(&[fence], true, 10_000_000_000)
            .map_err(error)?;
        std::ptr::copy_nonoverlapping(mapped, data.as_mut_ptr(), data.len());
    }
    let mut result = crate::math::validate(&data);
    result["device"] = properties(index, &props);
    result["allocation_bytes"] = json!(required.size);
    result["note"] = json!("Tiny F32 Vulkan kernel only. Does not establish GGML quantized math, model fit, song generation, speed or sustained thermal safety.");
    unsafe {
        device.unmap_memory(memory);
        device.destroy_fence(fence, None);
        device.destroy_command_pool(command_pool, None);
        device.destroy_descriptor_pool(pool, None);
        device.destroy_pipeline(pipeline, None);
        device.destroy_shader_module(module, None);
        device.destroy_pipeline_layout(layout, None);
        device.destroy_descriptor_set_layout(set_layout, None);
        device.destroy_buffer(buffer, None);
        device.free_memory(memory, None);
        device.destroy_device(None);
        instance.destroy_instance(None);
    }
    Ok(result)
}
