"""Read public instance method names from original class bytes, without execution.

未测试，待后续统一验证。 This is a narrow dependency gate, not a full ABI check.
"""
import io
import struct


def public_instance_methods(raw):
    stream = io.BytesIO(raw)

    def read(size):
        value = stream.read(size)
        if len(value) != size:
            raise ValueError("Truncated class file")
        return value

    def u2():
        return struct.unpack(">H", read(2))[0]

    def u4():
        return struct.unpack(">I", read(4))[0]

    if u4() != 0xCAFEBABE:
        raise ValueError("Invalid class file")
    read(4)  # minor/major version
    pool = [None] * u2()
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
             11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < len(pool):
        tag = read(1)[0]
        if tag == 1:
            pool[index] = read(u2()).decode("utf-8", errors="replace")
        elif tag in sizes:
            read(sizes[tag])
        else:
            raise ValueError("Unknown constant-pool tag")
        index += 2 if tag in (5, 6) else 1
    read(6)  # access, this, super
    read(2 * u2())  # interfaces

    def members(collect=False):
        result = set()
        for _ in range(u2()):
            access, name, descriptor = u2(), u2(), u2()
            if name >= len(pool) or descriptor >= len(pool):
                raise ValueError("Invalid class member index")
            if collect and access & 0x0001 and not access & 0x0008:
                result.add(pool[name])
            for _ in range(u2()):
                u2()
                read(u4())
        return result

    members()  # fields
    return members(collect=True)
