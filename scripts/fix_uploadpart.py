#!/usr/bin/env python3
"""Fix uploadPart DSL calls in S3ServerM3Test.kt.

Transforms:
    val r = s3.uploadPart {
        it.bucket(bucket).key(key).uploadId(init.uploadId())
            .partNumber(i).contentLength(payload.size.toLong())
    }.eTag()
Into:
    val r = s3.uploadPart(
        { it.bucket(bucket).key(key).uploadId(init.uploadId())
            .partNumber(i).contentLength(payload.size.toLong()) },
        RequestBody.fromBytes(payload)
    ).eTag()

The tricky part is figuring out the payload variable name. We look for
the line above the s3.uploadPart call that defines a ByteArray.
"""
import re
import sys

def fix(content):
    # Match blocks like:
    #   [optional payload line]
    #   s3.uploadPart {
    #       it.... (any lines until)
    #   }.eTag()  OR  }  (when result not used)
    #
    # We need to find the payload variable. Look at the preceding line
    # for 'val X = ByteArray(...)'.
    lines = content.split('\n')
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if 's3.uploadPart {' in line:
            # Find the payload variable by scanning backwards
            payload_var = None
            for j in range(len(out) - 1, max(-1, len(out) - 5), -1):
                m = re.search(r'val\s+(\w+)\s*=\s*ByteArray', out[j])
                if m:
                    payload_var = m.group(1)
                    break
            if payload_var is None:
                out.append(line)
                i += 1
                continue
            # Collect the block until the closing }
            block_indent = len(line) - len(line.lstrip())
            block_lines = []
            i += 1
            while i < len(lines):
                bl = lines[i]
                if bl.strip() == '}' or bl.strip().startswith('}.'):
                    break
                block_lines.append(bl)
                i += 1
            # Now i points at the closing line. Build the replacement.
            # The consumer lambda: { it.... }
            consumer_body = ' '.join(l.strip() for l in block_lines)
            closing = lines[i]  # e.g. '            }.eTag()' or '}'
            # Reconstruct indentation
            indent = ' ' * block_indent
            new_line = f"{indent}s3.uploadPart("
            new_line += f"{{ {consumer_body} }},"
            new_line += f" RequestBody.fromBytes({payload_var})"
            new_line += f"){closing.strip().lstrip('}').lstrip('.').strip() and '.' + closing.strip().lstrip('}').lstrip('.').strip() or ''}"
            # Simpler: append the trailing part after }
            trailing = closing.strip()[1:]  # remove leading }
            new_line = f"{indent}s3.uploadPart({{ {consumer_body} }}, RequestBody.fromBytes({payload_var})){trailing}"
            out.append(new_line)
            i += 1
        else:
            out.append(line)
            i += 1
    return '\n'.join(out)

if __name__ == '__main__':
    with open(sys.argv[1]) as f:
        content = f.read()
    fixed = fix(content)
    with open(sys.argv[1], 'w') as f:
        f.write(fixed)
    print('Fixed')
