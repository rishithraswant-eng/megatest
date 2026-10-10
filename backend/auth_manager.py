import hashlib
import os
from typing import Optional, Dict, Any
from .database import get_db_connection

def hash_password(password: str, salt: bytes = None) -> str:
    if salt is None:
        salt = os.urandom(16)
    hashed = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 100000)
    return f"{salt.hex()}:{hashed.hex()}"

def verify_password(password: str, password_hash: str) -> bool:
    try:
        salt_hex, hash_hex = password_hash.split(':')
        salt = bytes.fromhex(salt_hex)
        hashed = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 100000)
        return hashed.hex() == hash_hex
    except Exception:
        return False

def login_admin(username: str, password: str) -> Optional[Dict[str, Any]]:
    """
    Login endpoint checking admin_credentials using real hashing.
    Username is mapped to user's display_name or user_id.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT a.admin_id, a.password_hash, a.role_level, a.department_id, u.org_id
        FROM admin_credentials a
        JOIN users u ON a.user_id = u.user_id
        WHERE u.user_id = ? OR u.display_name = ?
    """, (username, username))
    row = cursor.fetchone()
    conn.close()
    
    if not row:
        return None
        
    if verify_password(password, row['password_hash']):
        return {
            'admin_id': row['admin_id'],
            'role_level': row['role_level'],
            'org_id': row['org_id'],
            'department_id': row['department_id']
        }
    return None

def check_department_access(admin_id: str, department_id: str) -> bool:
    """
    Checks if an admin can see/act on incidents for a specific department.
    super_admin or facilities_admin sees everything in their org.
    department_admin only sees their assigned department.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT a.role_level, a.department_id, u.org_id
        FROM admin_credentials a
        JOIN users u ON a.user_id = u.user_id
        WHERE a.admin_id = ?
    """, (admin_id,))
    admin = cursor.fetchone()
    if not admin:
        conn.close()
        return False
        
    role = admin['role_level']
    admin_org = admin['org_id']
    admin_dept = admin['department_id']
    
    cursor.execute("SELECT org_id FROM departments WHERE department_id = ?", (department_id,))
    dept = cursor.fetchone()
    conn.close()
    
    if not dept:
        return False
        
    if dept['org_id'] != admin_org:
        return False
        
    if role in ['super_admin', 'facilities_admin']:
        return True
    elif role == 'department_admin':
        return admin_dept == department_id
        
    return False
